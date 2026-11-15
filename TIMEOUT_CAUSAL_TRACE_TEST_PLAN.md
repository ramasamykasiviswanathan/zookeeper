# ZooKeeper timeout, causal tracing, concurrency, and many-operation test plan

This document is the research test specification for the ZooKeeper 3.9.6 checkout in `/Users/ramasamykasiviswanathan/Source_Code/zooKeeper3_9` and the Kazoo client notebook at `/Users/ramasamykasiviswanathan/Source_Code/Python_notebook/Concurrency_test_new.ipynb`.

It covers normal requests, one delayed request, concurrent requests, and many operations. It also separates three mechanisms that are often incorrectly grouped as “timeout”:

1. **Server processing latency** — time spent in the ZooKeeper request pipeline.
2. **Client request timeout** — Java client's `zookeeper.request.timeout`; returns `KeeperException.Code.REQUESTTIMEOUT`.
3. **Session timeout/expiry** — the server expires an idle session after the negotiated session timeout.

## 1. Code findings that determine the test design

### Delay injector

`DelayInjector` parses:

```text
-Dzookeeper.test.delayPerOpcode=<opcode>:<category>:<seconds>[,...]
```

- Categories: `1=SLOW` (sleep), `2=HEAVY` (busy-loop), `3=NETWORK` (sleep plus thread rename).
- The duration is **seconds**. Older notes that describe this value as minutes are incorrect for the current implementation.
- The optional stage filter is `-Dzookeeper.test.delayStages=STAGE1,STAGE2`.
- The properties are read in a static initializer. Restart every server after changing them.
- `getData=4`, `setData=5`, and `getChildren=8` are the currently targeted opcodes.
- `create=1` is suitable for test setup because it is not one of the configured delayed opcodes.

### 1.1 Which stages to inject delay and why

Only the **14 request-aware stages** can actually delay a configured opcode (the other 8 stage names have no `Request`/opcode and are skipped). No single request traverses every effective stage — a `setData` on the leader crosses the write pipeline, while a `getData`/`getChildren` is a local read served by the connected server.

| Stage | Fires for `setData` (leader write)? | Fires for `getData` (local read)? | Why inject here |
|-------|-------------------------------------|-----------------------------------|-----------------|
| `SERVER_RECV` | ✅ | ✅ | Earliest point; every client request on the connected server — measures pure entry latency |
| `LEADER` | ✅ | ❌ | Leader admission; isolates leader-side queuing |
| `PREP_ENQUEUE` | ✅ | ❌ | Write enters the leader prep queue |
| `PREP` | ✅ | ❌ | Write executes in prep processor (txn proposal assembly) |
| `PROPOSAL` | ✅ | ❌ | Write enters proposal handling |
| `LEADER_PROPOSE` | ✅ | ❌ | Quorum proposal broadcast — core write latency |
| `SYNC_ENQUEUE` | ✅ | ❌ | Write enters the transaction-log sync queue |
| `SYNC_0` | ✅ | ❌ | Write executes in sync processor (disk fsync point) |
| `COMMIT` | ✅ | ✅ | Reads enter commit; writes hit after quorum commit |
| `FINAL` | ✅ | ✅ | Last server stage before response send — closest to client-visible latency |
| `GETDATA` | ❌ | ✅ | getData-specific extra injection point, in addition to `FINAL` |
| `GETCHILDREN` | ❌ | ✅ | getChildren-specific extra injection point |
| `LEARNER` | ✅ (follower) | ❌ | Follower forwards write to leader — isolation of forwarding hop |
| `OBSERVER` | ✅ (observer) | ❌ | Observer inbound path |

**Recommended stage sets by research goal**

| Goal | Stage set (`ZOO_DELAY_STAGES`) | Rationale |
|------|-------------------------------|-----------|
| Entry latency only | `SERVER_RECV` | Delays before any processing |
| Leader processing only | `PREP,LEADER_PROPOSE` | Isolates leader-side write work |
| Quorum commit latency | `SYNC_0,COMMIT` | Isolates disk fsync + quorum |
| Pre-response / client-visible | `FINAL` | Closest to what the client times |
| Full write pipeline | `SERVER_RECV,PREP,LEADER_PROPOSE,SYNC_0,COMMIT,FINAL` | End-to-end setData delay |
| Read vs. write contrast | Read: `GETDATA,FINAL` \| Write: full pipeline | Shows reads skip quorum stages |

## 2. Slow-injection configuration table (fraction-of-a-minute)

Use **category `1` (SLOW)** — it calls `TimeUnit.SECONDS.sleep()` and releases CPU, so it will not burn cores or starve pings like HEAVY. Durations are in **seconds**. The injector casts to `(long)`, so the minimum meaningful SLOW delay is **1 second** (e.g. `0.5` becomes `sleep(0)` → no delay). Restart every server after changing env vars because `DelayInjector` reads the properties in a static initializer.

JVM opts are applied through `docker-compose.yml` (`SERVER_JVMFLAGS` line 12), driven by env vars:
- `ZOO_DELAY_PER_OPCODE` → `-Dzookeeper.test.delayPerOpcode=<opcode>:1:<seconds>`
- `ZOO_DELAY_STAGES` → `-Dzookeeper.test.delayStages=<STAGE1,STAGE2,...>` (empty = all effective stages)

### Slow-injection matrix (all runs sub-minute)

| Goal | `ZOO_DELAY_PER_OPCODE` (JVM) | `ZOO_DELAY_STAGES` (JVM) | Kazoo `OPERATION_SEQUENCE` | Kazoo `SESSION_TIMEOUT_S` | Kazoo `NUM_CLIENTS` | Kazoo `OPS_PER_CLIENT` | Retries | Expected added latency |
|------|------------------------------|--------------------------|----------------------------|---------------------------|---------------------|------------------------|---------|------------------------|
| Baseline (no delay) | `` (empty) | `` (empty) | `("get",)` | 60 | 1 | 1 | False | ~0 s |
| Entry delay only | `5:1:5` | `SERVER_RECV` | `("set",)` | 60 | 1 | 1 | False | ~5 s |
| Leader prep only | `5:1:5` | `PREP` | `("set",)` | 60 | 1 | 1 | False | ~5 s |
| Quorum propose only | `5:1:5` | `LEADER_PROPOSE` | `("set",)` | 60 | 1 | 1 | False | ~5 s |
| Sync + commit | `5:1:5` | `SYNC_0,COMMIT` | `("set",)` | 60 | 1 | 1 | False | ~10 s |
| Pre-response (`FINAL`) | `5:1:5` | `FINAL` | `("set",)` | 60 | 1 | 1 | False | ~5 s |
| Full write pipeline | `5:1:5` | `SERVER_RECV,PREP,LEADER_PROPOSE,SYNC_0,COMMIT,FINAL` | `("set",)` | 60 | 1 | 1 | False | ~30 s |
| Read delay contrast | `4:1:5` | `GETDATA,FINAL` | `("get",)` | 60 | 1 | 1 | False | ~10 s |
| Concurrent write | `5:1:5` | `SERVER_RECV,LEADER_PROPOSE,COMMIT,FINAL` | `("set",)` | 60 | 3 | 1 | False | ~20 s + contention |
| Concurrent many-ops | `5:1:3` | `FINAL` | `("set",)` | 60 | 5 | 5 | False | ~15 s/client |

### Kazoo client configuration rationale

- `SESSION_TIMEOUT_S` must **exceed** the worst-case total injected delay plus a margin. All matrix rows stay ≤ 30 s, so `60 s` is safe and satisfies `minSessionTimeout=20000` / `maxSessionTimeout=600000` from `docker-entrypoint.sh`. If session timeout < total delay, the session expires mid-delay → `ConnectionLoss`/`SessionExpiredError`, which confuses timeout research.
- `ENABLE_OPERATION_RETRIES = False` for all primary rows (a retried `setData` can have an ambiguous committed outcome — see rule #2).
- `CONNECTION_START_TIMEOUT_S = 30` is ample for sub-minute delays.
- `OPERATION_SEQUENCE = ("set",)` targets opcode 5 (write, full quorum pipeline). Use `("get",)` for opcode 4 (local read).

### Apply via docker-compose

```bash
# Example: 5 s SLOW delay on setData at FINAL only
ZOO_DELAY_PER_OPCODE='5:1:5' ZOO_DELAY_STAGES='FINAL' docker compose up -d --force-recreate
docker compose restart   # required: DelayInjector reads props in static initializer
```

## 3. Notebook modification required for `setData` (opcode 5)

The notebook already supports `set` in `invoke_operation` (cell `zk-research-06`) and `result_summary` (same cell), and `worker()` creates the target znode via `create` (opcode 1, not a delayed opcode). The only change needed is the **operation sequence**.

### Cell `zk-research-04` — change `OPERATION_SEQUENCE`

**Before:**
```python
OPERATION_SEQUENCE = ("get",)   # opcode 4
```

**After (setData only):**
```python
# setData = opcode 5 (write, full quorum pipeline).
OPERATION_SEQUENCE = ("set",)
```

**After (read vs. write comparison):**
```python
# Alternate between get (opcode 4, local read) and setData (opcode 5, write).
OPERATION_SEQUENCE = ("get", "set")
```

### Why this is sufficient for `setData` research

- `invoke_operation` → `client.set(path, payload)` returns a `Stat`; `result_summary` reports `version={value.version}`.
- `worker()` already calls `ensure_path(root)` and `client.create(path, b"initial")`, so a znode exists for `setData` to update.
- Keep `ENABLE_OPERATION_RETRIES = False` so a lost-response `setData` is reported as `FAILED` rather than silently re-applied (retries risk duplicate/apply-ambiguity).

### Verifying a `setData` actually committed (rule #2)

Never infer a failed `setData` did not commit. After each measured `set`, read back the same path and compare `stat.version` and `data`, and correlate `zxid`/session/cxid in server logs:

```bash
SESSION_ID=<notebook session id>
grep -E "ZK_OPCODE_LOG.*(sessionID=${SESSION_ID}|Session: ${SESSION_ID})" zookeeper-*.log
grep -E 'ZK_OPCODE_LOG.*opcode=setData' zookeeper-*.log
```

### Complete stage inventory

There are **23 injection call sites** in the main source (0 in the test source), but only **22 unique stage names** (because `COMMIT` is instrumented at two call sites) and only **14 request-aware stage names that can currently delay a configured opcode**.

#### Effective request-aware stages

These calls receive a `Request`, so the injector can resolve the opcode and apply `delayPerOpcode`:

| Stage | Where it runs | Typical request scope |
|---|---|---|
| `SERVER_RECV` | `ZooKeeperServer.processPacket` | Every client request, on the connected server |
| `LEADER` | `LeaderRequestProcessor` | Requests accepted by the leader |
| `PREP_ENQUEUE` | `PrepRequestProcessor.processRequest` | Requests entering the leader prep queue |
| `PREP` | `PrepRequestProcessor.pRequest` | Requests executing in the leader prep processor |
| `PROPOSAL` | `ProposalRequestProcessor` | Requests entering leader proposal handling |
| `LEADER_PROPOSE` | `Leader.propose` | Writes proposed for quorum |
| `SYNC_ENQUEUE` | `SyncRequestProcessor.processRequest` | Writes entering a transaction-log sync queue |
| `SYNC_0` | `SyncRequestProcessor.run` after dequeue | Writes executing in the sync processor |
| `COMMIT` | `CommitProcessor.processRequest` **and** `CommitProcessor.commit` | Reads enter commit processing; writes can hit this filter at enqueue and again after quorum commit |
| `FINAL` | `FinalRequestProcessor.processRequest` | Reads and writes before the final response |
| `GETDATA` | `FinalRequestProcessor` `getData` case | `getData` only; occurs in addition to `FINAL` |
| `GETCHILDREN` | `FinalRequestProcessor` `getChildren` case | `getChildren` only; occurs in addition to `FINAL`; does not cover `getChildren2` |
| `LEARNER` | `Learner.request` | Writes forwarded from a follower/observer to the leader |
| `OBSERVER` | `ObserverRequestProcessor` | Requests received by an observer |

Use this explicit list when the test intends to cover every effective stage:

```text
SERVER_RECV,LEADER,PREP_ENQUEUE,PREP,PROPOSAL,LEADER_PROPOSE,SYNC_ENQUEUE,SYNC_0,COMMIT,FINAL,GETDATA,GETCHILDREN,LEARNER,OBSERVER
```

Leaving `ZOO_DELAY_STAGES` empty has the same effect as this list **for request-aware stages**, because `DelayInjector` ignores stage-only calls that have no `Request`.

#### Declared but currently inactive stage-only points

These eight unique stages have call sites but no request/opcode context. The current injector overload for them returns without delaying anything:

| Stage | Call site | Reason inactive |
|---|---|---|
| `SYNC` | `SyncRequestProcessor.run` poll loop | No `Request` |
| `FOLLOWER_RECV` | `Follower.processPacket` | No `Request` |
| `ACK_PROC` | `Leader.processAck` | Only `zxid`/follower context at the injection point |
| `LEADER_COMMIT` | `Leader.commit(long zxid)` | Only `zxid` at the injection point |
| `LEARNER_SEND` | `Learner.writePacket` | No `Request`/opcode |
| `LEARNER_RECV` | `Learner.readPacket` | No `Request`/opcode |
| `ACK_RECV` | `LearnerHandler` ACK loop | No `Request`/opcode |
| `OBSERVER_RECV` | `Observer.processPacket` | No `Request`/opcode |

Therefore, the old statement “SLOW on all 22 stages = 2 seconds × 22 = 44 seconds” is invalid. Eight stages are no-ops, and no single request traverses every request-aware stage. These quorum-packet points should be tested only after adding a separate stage-wide/quorum-packet delay mode; they must not be conflated with per-opcode request delay.

### Timeout semantics

- The Java client property `zookeeper.request.timeout` is disabled by default (`0`). When enabled, `ClientCnxn.waitForPacketFinish` waits for that many milliseconds, returns `REQUESTTIMEOUT`, and aborts the connection.
- Kazoo's `KazooClient(timeout=...)` is the requested **session timeout**, not a per-request deadline. Kazoo does not implement ZooKeeper's Java `zookeeper.request.timeout` feature.
- `maxSessionTimeout` and `minSessionTimeout` are `zoo.cfg` settings in this branch; there is no supported JVM property for them. Defaults are `20 * tickTime` and `2 * tickTime`.
- `globalOutstandingLimit` limits outstanding requests across the ensemble. The documented default is 1000.
- `zookeeper.request_stale_latency_check` is disabled by default. Enable it only for an explicit stale-request test; enabling it changes whether a request older than the session timeout is dropped.

### Causal identifiers

Use these identifiers according to scope:

| Identifier | Scope | Use |
|---|---|---|
| `sessionID` | ZooKeeper session | Join Kazoo client to server requests |
| `cxid` | Request within a session | Join a client attempt to the server pipeline |
| `zxid` | Committed transaction | Follow a write through leader/follower commit stages |
| `selfId` | One in-memory `Request` object on one server | Match BEFORE/AFTER lifecycle records on that host |
| `host` + timestamp | Server process | Disambiguate replicated copies |

`selfId` is not assumed to be stable across hosts. The stable client/server join key is `sessionID + cxid`; the stable replicated write key is `zxid`.

## 2. Primary test matrix

Run each delayed row with the **same** client scenario as the corresponding baseline row. Repeat each case at least three times after a clean server restart; report median and p95.

| ID | Workload | Server delay configuration | Client configuration | Primary observations | Expected outcome / acceptance criterion |
|---|---|---|---|---|---|
| B1 | Normal, single `getData` | No opcode delay | `SCENARIO=single`, 1 client, 1 `get` | Baseline latency; complete server trace | Operation succeeds; trace contains `SERVER_RECV` → role-specific prep/commit → `FINAL`/`GETDATA` |
| B2 | Normal, concurrent `getData` | No opcode delay | `SCENARIO=concurrent`, 3 clients, 1 `get` each | Baseline p50/p95 and overlap | All requests succeed; separate session/cxid traces are visible |
| B3 | Normal, many `getData` | No opcode delay | `SCENARIO=many`, 5 clients, 20 `get` each | Throughput and tail latency; queue growth | All operations recorded separately; no unexplained client failures |
| S1 | Single slow `getData`, all effective stages, client connects to **leader** | `4:1:2`; explicit 14-stage list | One client pinned to the leader's host; 1 `get` | Count delay events and end-to-end latency | Connected-leader read path has 8 delay hits: `SERVER_RECV`, `LEADER`, `PREP_ENQUEUE`, `PREP`, `PROPOSAL`, `COMMIT`, `FINAL`, `GETDATA`; expected local-path delay is about 16 s |
| S2 | Same read, client connects to a **follower** | Same as S1 | One client pinned to a follower host | Compare local read path | Follower read path has 4 hits: `SERVER_RECV`, `COMMIT`, `FINAL`, `GETDATA`; expected local-path delay is about 8 s |
| S3 | Single slow `getChildren`, all effective stages | `8:1:2`; explicit 14-stage list | One client pinned to leader and follower in separate runs | Verify opcode-specific final stage | `GETCHILDREN` is delayed instead of `GETDATA`; `getChildren2` is not covered by opcode 8 |
| S4 | Single slow `setData`, all effective stages | `5:1:2`; explicit 14-stage list | One client; pinned leader/follower variants | Write path, zxid, quorum and commit order | Success; connected-leader local path has 11 hits (including `COMMIT` twice), connected-follower runs also include `LEARNER`; distributed quorum latency is measured, not predicted by simple multiplication |
| S5 | Single slow `getData`, one isolated stage | `4:1:2`; `ZOO_DELAY_STAGES=SERVER_RECV` | One client | Isolated stage latency | Success; approximately one 2 s interval attributable to `SERVER_RECV` |
| S6 | CPU contention comparison | `4:2:2`; same explicit stage list as S1 | Same as S1 | HEAVY versus SLOW side effects | HEAVY may increase unrelated request/ping latency; use only for contention research |
| C1 | Concurrent slow reads, all effective stages | `4:1:2`; explicit 14-stage list | `SCENARIO=concurrent`, 3 clients, 1 `get` each | Per-request latency, contention, connection states | Every attempt has a recorded outcome; failures are explicit and are not silently retried |
| C2 | Concurrent slow writes, all effective stages | `5:1:2`; explicit 14-stage list | `SCENARIO=concurrent`, 3 clients, 1 `set` each | Commit/ack/quorum path | Successful writes have distinct zxids; failed attempts remain visible |
| M1 | Many slow reads, all effective stages | `4:1:2`; explicit 14-stage list | `SCENARIO=many`, 5 clients, 20 `get` each | Queueing, tail latency, state changes | All 100 attempt rows exist; p95 and throughput are comparable to B3 |
| M2 | Many mixed operations, all effective stages | `4:1:2,5:1:2,8:1:2`; explicit 14-stage list | `SCENARIO=many`, 5 clients, sequence `get,set,children` | Interleaved causal paths | Every attempt has a recorded outcome; writes can be followed by zxid |
| T1 | Session-expiry boundary | Block/lose the session connection; do not rely only on a long operation delay | Session timeout above server minimum; no operation retry | Session survives versus expires | A long pipeline delay alone need not expire a live session; expiry requires the session to stop being refreshed |
| T2 | Java request timeout | Delay or packet loss that prevents a response | Java client `-Dzookeeper.request.timeout=5000` | Exception code and elapsed time | `KeeperException.Code.REQUESTTIMEOUT` at about 5 seconds; connection is aborted |
| T3 | Explicit retry behavior | Force `ConnectionLoss` | Kazoo with `ENABLE_OPERATION_RETRIES=False`, then `True` | First failure versus later attempts | Disabled mode has one attempt; enabled mode retains all attempts. Do not blindly retry writes |
| T4 | Outstanding-request throttling | Default server limit | Many concurrent clients/operations | `THROTTLEDOP` and stalled requests | No throttling under default limit; enable a low limit only in a dedicated throttling test |

### 2.1 Role-specific stage matrix

A “hit” means that the named stage actually calls the injector for that request on the connected server. Replicated work on other servers is additional distributed delay and must be correlated separately.

| Effective stage | `getData` on leader | `getData` on follower | `setData` on leader | `setData` on follower | Notes |
|---|---:|---:|---:|---:|---|
| `SERVER_RECV` | yes | yes | yes | yes | Connected server receives the client request |
| `LEADER` | yes | no for local read; yes later for forwarded write | yes | yes later on leader | Leader-only processor |
| `PREP_ENQUEUE` | yes | no for local read; yes later for forwarded write | yes | yes later on leader | Leader prep queue |
| `PREP` | yes | no for local read; yes later for forwarded write | yes | yes later on leader | Leader prep execution |
| `PROPOSAL` | yes | no for local read; yes later for forwarded write | yes | yes later on leader | Leader proposal processor |
| `LEADER_PROPOSE` | no | no | yes | yes later on leader | Writes only |
| `SYNC_ENQUEUE` | no | no | yes on leader; yes on replicas receiving proposal | yes on leader and replicas | Writes only; log each host separately |
| `SYNC_0` | no | no | yes on leader; yes on replicas receiving proposal | yes on leader and replicas | Writes only; log each host separately |
| `COMMIT` | once | once | twice on leader (enqueue + post-quorum commit) | once on each replica at post-quorum commit | The filter name does not distinguish the two call sites |
| `FINAL` | yes | yes | yes | yes | Final processor; `FINAL` occurs before specialized final stage |
| `GETDATA` | yes | yes | no | no | Opcode 4 only |
| `GETCHILDREN` | no | no | no | no | Opcode 8 only; not `getChildren2` |
| `LEARNER` | no | no | no | yes | Follower/observer forwards a write to leader |
| `OBSERVER` | no | no | no | no in this 5-voting-node ensemble | Observer-only; this ensemble has no observer |

For S1/S2, “local-path delay” is the sum of the delay hits on the connected server only. For S4, write latency includes follower replication/ACK and quorum commit, so report the measured client latency and per-host stage events separately.

## 3. Exact server configuration

### 3.1 Recommended `zoo.cfg`

The current Docker entry point writes `tickTime=10000`, making the default session range 20–200 seconds. The experiment should use explicit bounds so the client request is not silently negotiated to a different value:

```properties
tickTime=10000
initLimit=10
syncLimit=5
minSessionTimeout=20000
maxSessionTimeout=600000
clientPort=2181
admin.enableServer=true
```

For an ensemble-wide 60-second client session timeout, `maxSessionTimeout=600000` permits it. Always inspect the server startup line for the effective negotiated timeout.

### 3.2 Delay properties

Baseline (normal requests): leave `ZOO_DELAY_PER_OPCODE` unset. The Compose default now passes an empty `-Dzookeeper.test.delayPerOpcode=`, which the injector treats as disabled.

```bash
cd /Users/ramasamykasiviswanathan/Source_Code/zooKeeper3_9
docker compose up -d --force-recreate
```

Delayed configuration (two-second SLOW delays on getData/setData/getChildren, all effective request-aware stages):

```bash
ALL_EFFECTIVE_STAGES='SERVER_RECV,LEADER,PREP_ENQUEUE,PREP,PROPOSAL,LEADER_PROPOSE,SYNC_ENQUEUE,SYNC_0,COMMIT,FINAL,GETDATA,GETCHILDREN,LEARNER,OBSERVER'

ZOO_DELAY_PER_OPCODE='4:1:2,5:1:2,8:1:2' \
ZOO_DELAY_STAGES="$ALL_EFFECTIVE_STAGES" \
docker compose up -d --force-recreate
```

Leaving `ZOO_DELAY_STAGES` empty has the same request-aware behavior, but the explicit list makes the intended coverage visible in the run command and protects against future stage-only call sites becoming active.

Single-stage attribution:

```bash
ZOO_DELAY_PER_OPCODE='4:1:2,5:1:2,8:1:2' \
ZOO_DELAY_STAGES='SERVER_RECV' \
docker compose up -d --force-recreate
```

HEAVY CPU contention:

```bash
ZOO_DELAY_PER_OPCODE='4:2:2' ZOO_DELAY_STAGES='' \
docker compose up -d --force-recreate
```

The properties are applied to all five servers through `SERVER_JVMFLAGS`. Because `DelayInjector` reads them during static initialization, use `--force-recreate` (or `docker compose down` followed by `up -d --build`) after changing them.

### 3.3 Optional server diagnostics

Keep stale-request dropping disabled during the primary comparison:

```text
-Dzookeeper.request_stale_connection_check=true
-Dzookeeper.request_stale_latency_check=false
```

Enable stale latency checking only for T1's explicit variant:

```text
-Dzookeeper.request_stale_latency_check=true
```

Do not combine a low `globalOutstandingLimit` with the latency tests unless request throttling is the variable under study.

## 4. Exact client configuration and notebook changes

The updated notebook has four switchable scenarios:

| Notebook variable | `baseline` | `single` | `concurrent` | `many` |
|---|---:|---:|---:|---:|
| `NUM_CLIENTS` | 1 | 1 | 3 | 5 |
| `OPERATIONS_PER_CLIENT` | 1 | 1 | 1 | 20 |
| `OPERATION_SEQUENCE` | `("get",)` | `("get",)` | `("get",)` | configurable |
| `ENABLE_OPERATION_RETRIES` | `False` | `False` | `False` | `False` |

Important client behavior:

- `SESSION_TIMEOUT_S=60.0` is the requested ZooKeeper session timeout.
- `CONNECTION_START_TIMEOUT_S=30.0` bounds initial connection establishment; it is not a per-operation timeout.
- `command_retry` is single-attempt, and measured calls are made directly so the original exception remains visible.
- `connection_retry` may reconnect indefinitely; this allows observation of `SUSPENDED`/`LOST` states but does not retry the measured operation.
- Every attempt records latency, outcome, exception, session ID, client state, path, and UTC/epoch times.
- Setup uses `create` (opcode 1), outside the delayed opcode set.
- Concurrent workers use a barrier so the first request is genuinely overlapping.
- Output is written to `zk_timeout_results/<RUN_ID>/` as CSV and JSON.

Recommended execution sequence:

1. Set server to baseline (remove delay property), restart, set notebook `SCENARIO="baseline"`, and execute.
2. Set server to delayed configuration, restart, execute the same scenario.
3. Repeat for `single`, `concurrent`, and `many` without changing the client operation sequence between baseline and delayed pairs.
4. Archive the server logs together with each run directory.

Example for the concurrent case:

```python
SCENARIO = "concurrent"
OPERATION_SEQUENCE = ("get",)
ENABLE_OPERATION_RETRIES = False
SESSION_TIMEOUT_S = 60.0
```

Example for the many-operation case:

```python
SCENARIO = "many"
OPERATION_SEQUENCE = ("get", "set", "children")
ENABLE_OPERATION_RETRIES = False
SESSION_TIMEOUT_S = 60.0
```

## 5. Causal-trace procedure

### 5.1 Join client results to server logs

1. Read `attempts.csv` and select the failed or slow attempt.
2. Match `session_id` (decimal) to server `sessionID` or `Session:`.
3. Within that session, match the request `cxid`. Kazoo does not expose its `cxid` publicly, so use operation order and the epoch time window to identify it, then verify opcode/path where available.
4. For `setData`, record the resulting `zxid` and follow it across leader/follower commit stages.
5. Match `selfId` only within one host and one in-memory request.

Example:

```bash
SESSION_ID=216178895678144512
grep -E "ZK_OPCODE_LOG.*(sessionID=${SESSION_ID}|Session: ${SESSION_ID})" zookeeper-*.log
```

The current delay event format is:

```text
ZK_OPCODE_LOG [STAGE][opcode=getData][opcodeNum=4] SLOW delay (2.0) seconds ...
```

It does not contain `sessionID` or `cxid`. For strict per-request attribution, change `DelayInjector.injectInternal` to receive the `Request` and add `sessionId`/`cxid` to the log message. Until then, correlate by host, opcode, and time, then confirm against the matching session/cxid lifecycle entries.

### 5.2 Expected request order

For a read:

```text
client send → SERVER_RECV → PREP → FINAL → response send/COMPLETE → client receive
```

For a write:

```text
client send → SERVER_RECV → PREP → SYNC → leader propose → quorum ACK/commit
→ follower commit → FINAL → response send/COMPLETE → client receive
```

Acceptance checks:

- No orphan client attempt without a matching server path or disconnect reason.
- Successful writes have one committed zxid and an end-to-end response.
- Concurrent requests have distinct session/cxid pairs and do not overwrite one another's trace.
- A timed-out/failed request is not reported as successful merely because a retry later succeeded.

## 6. Java-client request-timeout test (T2)

Kazoo cannot produce `REQUESTTIMEOUT`, so use the Java client for that specific mechanism. The repository already contains `ClientRequestTimeoutTest`, which validates response loss and spurious wakeup behavior.

Run the existing unit tests:

```bash
cd /Users/ramasamykasiviswanathan/Source_Code/zooKeeper3_9
mvn -pl zookeeper-server -Dtest=ClientRequestTimeoutTest test
```

For an end-to-end Java-client experiment:

```bash
java -Dzookeeper.request.timeout=5000 \
  -cp <client-and-server-classpath> <research-client-main>
```

Expected result:

- The operation raises `KeeperException` with code `REQUESTTIMEOUT`.
- Elapsed wait is approximately 5000 ms (allow a small scheduling margin).
- The client aborts the connection because the response may have been lost.

## 7. Metrics and reporting

Report one row per run with:

- scenario, delay category/duration/stages, server configuration hash;
- clients, operations/client, total logical operations;
- success count, failure count by exception type;
- p50/p95/p99/max successful latency;
- wall duration and operations/second;
- `SUSPENDED`, `LOST`, and reconnect counts;
- server stage latency and delay count from logs;
- whether the session was recreated (session expiry invalidates old locks/ephemeral nodes).

Compare delayed and baseline runs using the same operation sequence. Plot latency against:

- number of concurrent clients;
- operations per client;
- delay duration;
- delay category (SLOW versus HEAVY);
- stage count.

## 8. Safety and correctness rules

1. Keep operation retries disabled for primary measurements.
2. Never infer that a failed `setData` did not commit; verify znode version/data and zxid.
3. Do not use a 2-second session timeout unless the server explicitly allows it (`minSessionTimeout`); the recommended minimum is 20 seconds.
4. Use `SLOW` for deterministic latency attribution; reserve `HEAVY` for CPU-contention experiments.
5. Do not assume a long operation necessarily expires a live session. Test connection loss/session expiry explicitly.
6. Keep `request_stale_latency_check=false` for baseline comparisons.
7. Do not change `tickTime`, delay properties, and client operation sequence simultaneously.
8. Archive server logs and client artifacts under the same run ID.

## 9. Known implementation gaps

- `DelayInjector` stage-level overloads without a `Request` are skipped, so not every stage named in older documentation can currently receive a delay.
- The delay log lacks `sessionID` and `cxid`, limiting exact concurrent-request attribution.
- The detailed lifecycle format and DAG sequence format use different fields; a parser should normalize both formats before producing a causal graph.
- `selfId`/`parentTraceId` semantics differ between formats. Do not infer a DAG edge solely from a positive/negative parent value.
- The notebook uses Kazoo; a Java client is required for exact `REQUESTTIMEOUT` validation.

## 9.5 Concurrent slow-processing execution sequence (multi-client barrier)

The new notebook `Concurrent_Slow_Processing_Test.ipynb` runs multiple Kazoo clients
concurrently against the same ensemble, releasing all measured operations through a
`threading.Barrier` so the first request from each client overlaps in wall time.

Execute it as follows:

```bash
# 1. Baseline: no server delay, concurrent multi-client reads
ZOO_DELAY_PER_OPCODE='' ZOO_DELAY_STAGES='' docker compose up -d --force-recreate
docker compose restart
# In notebook: SCENARIO="concurrent", NUM_CLIENTS=5, OPERATIONS_PER_CLIENT=3,
# OPERATION_SEQUENCE=("get",), ENABLE_OPERATION_RETRIES=False

# 2. Delayed: 5 s SLOW on setData at FINAL, concurrent multi-client writes
ZOO_DELAY_PER_OPCODE='5:1:5' ZOO_DELAY_STAGES='FINAL' docker compose up -d --force-recreate
docker compose restart
# In notebook: same client settings but OPERATION_SEQUENCE=("set",)

# 3. Full pipeline: all effective write stages delayed
ZOO_DELAY_PER_OPCODE='5:1:5' ZOO_DELAY_STAGES='SERVER_RECV,PREP,LEADER_PROPOSE,SYNC_0,COMMIT,FINAL' \
  docker compose up -d --force-recreate
docker compose restart
```

Compare baseline vs delayed using identical `NUM_CLIENTS`, `OPERATIONS_PER_CLIENT`, and
`OPERATION_SEQUENCE`. Archive each run directory (`zk_concurrent_slow_results/<RUN_ID>/`)
together with the matching server logs.

Key metrics for concurrent slow-processing research:

| Metric | What it reveals |
|--------|-----------------|
| Wall duration vs sum of client latencies | Thread-pool / queue saturation on the server |
| p95 latency growth as `NUM_CLIENTS` increases | Quorum processor contention under overlap |
| `ConnectionLoss` / `SessionExpiredError` count | Session timeout too small for total injected delay |
| Distinct zxids for successful writes | Each committed write is unique (no silent duplicate) |
| `ZK_OPCODE_LOG` delay count per stage | Whether each configured stage actually fired |

## 10. Completion checklist

- [ ] Baseline single run succeeds and is archived.
- [ ] Delayed single run succeeds and has a complete causal path.
- [ ] Baseline/delayed concurrent pair has identical client operation settings.
- [ ] Baseline/delayed many-operation pair has identical client operation settings.
- [ ] Every client attempt appears exactly once in `attempts.csv` (plus explicit retry rows).
- [ ] Every connection transition appears in `states.csv`.
- [ ] At least three repetitions are aggregated.
- [ ] Java `ClientRequestTimeoutTest` passes.
- [ ] Delayed-log entries include session/cxid, or the report documents the time-based fallback.
