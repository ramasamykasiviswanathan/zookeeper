Here is the **complete list of stage strings that are actually injected in the Java codebase, not in `DelayInjector` itself**, as declared in the call sites across the server.

### All `DelayInjector.inject(stage, ...)` declarations found

| File                                                                                              | Line | Stage used       | Request / null |
| ------------------------------------------------------------------------------------------------- | ---- | ---------------- | -------------- |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/FinalRequestProcessor.java`           | 148  | `FINAL`          | Request        |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/FinalRequestProcessor.java`           | 396  | `GETDATA`        | Request        |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/FinalRequestProcessor.java`           | 482  | `GETCHILDREN`    | Request        |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/PrepRequestProcessor.java`            | 756  | `PREP`           | Request        |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/PrepRequestProcessor.java`            | 1047 | `PREP_ENQUEUE`   | Request        |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/SyncRequestProcessor.java`            | 166  | `SYNC`           | null           |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/SyncRequestProcessor.java`            | 180  | `SYNC_0`         | Si             |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/SyncRequestProcessor.java`            | 284  | `SYNC_ENQUEUE`   | Request        |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/ZooKeeperServer.java`                 | 1751 | `SERVER_RECV`    | Request        |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/CommitProcessor.java`          | 597  | `COMMIT`         | Request        |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/CommitProcessor.java`          | 610  | `COMMIT`         | Request        |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/Follower.java`                 | 158  | `FOLLOWER_RECV`  | null           |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/Leader.java`                   | 1051 | `ACK_PROC`       | null           |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/Leader.java`                   | 1229 | `LEADER_COMMIT`  | null           |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/Leader.java`                   | 1308 | `LEADER_PROPOSE` | Request        |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/LeaderRequestProcessor.java`   | 50   | `LEADER`         | Request        |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/Learner.java`                  | 201  | `LEARNER_SEND`   | null           |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/Learner.java`                  | 236  | `LEARNER_RECV`   | null           |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/Learner.java`                  | 257  | `LEARNER`        | Request        |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/LearnerHandler.java`           | 663  | `ACK_RECV`       | null           |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/Observer.java`                 | 180  | `OBSERVER_RECV`  | null           |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/ObserverRequestProcessor.java` | 138  | `OBSERVER`       | Request        |
| `zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/ProposalRequestProcessor.java` | 70   | `PROPOSAL`       | Request        |

### Uniqueness check

- All 22 distinct stage names above appear **once as a literal** per file; `COMMIT` appears in two places in the _same_ class but the stage name is identical, not duplicated as a new stage concept – the `Set<String>` used in `DelayInjector` will still de-duplicate it automatically.
- No two different stages share the same literal string across the code base.  
  i.e. the declaration side is free of accidental duplicate stage names.

That means the stage filter `ALLOWED_STAGES` built in `DelayInjector` can safely treat each of those strings as **unique identifiers**. The filter only controls which of those already-unique stage names are allowed to proceed; it never creates new stage semantics and it does not interfere with opcode priority, because the stage check is performed before the opcode lookup and the opcode spec is the one that decides whether a delay is actually applied.

With the new stage filter `-Dzookeeper.test.delayStages` and the per-opcode config `-Dzookeeper.test.delayPerOpcode` you can now run the same opcode set and isolate a single stage or a group of stages.  
The opcode config stays the same for your research runs:

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2
```

- 4 = getData → HEAVY 2 min
- 5 = setData → HEAVY 2 min
- 8 = getChildren → HEAVY 2 min

The stage filter defaults to _all_ stages when it is omitted or empty.

### Stage names that exist in the injection points

From the injection/declaration scan:

- `SERVER_RECV`
- `PREP_ENQUEUE`
- `PREP`
- `SYNC_ENQUEUE`
- `SYNC`
- `SYNC_0`
- `COMMIT`
- `FINAL`
- `GETDATA`
- `GETCHILDREN`
- `LEADER`
- `LEADER_PROPOSE`
- `LEADER_COMMIT`
- `ACK_PROC`
- `PROPOSAL`
- `OBSERVER`
- `OBSERVER_RECV`
- `FOLLOWER_RECV`
- `LEARNER`
- `LEARNER_SEND`
- `LEARNER_RECV`
- `ACK_RECV`

All names are stored in a `Set<String>` in `DelayInjector`, therefore unique and case-insensitive.

### Java opts per run

**Full pipeline – all stages**

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2
```

_No `-Dzookeeper.test.delayStages` → all 22 stages are allowed._

**Baseline – logging only, no burn**

```
-Dzookeeper.test.delayPerOpcode=4:2:0,5:2:0,8:2:0
```

Stage filter optional – will still log `NO_DELAY` for every stage.

**Single-stage runs – HEAVY 2 min for getData/setData/getChildren**

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=SERVER_RECV
```

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=PREP_ENQUEUE
```

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=PREP
```

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=SYNC_ENQUEUE
```

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=SYNC
```

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=COMMIT
```

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=FINAL
```

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=GETDATA
```

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=GETCHILDREN
```

**Quorum leader path**

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=LEADER_PROPOSE
```

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=LEADER_COMMIT
```

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=LEADER
```

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=PROPOSAL
```

**Follower / Observer / Learner**

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=FOLLOWER_RECV
```

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=OBSERVER_RECV
```

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=LEARNER
```

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=LEARNER_SEND
```

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=LEARNER_RECV
```

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=ACK_RECV
```

**Multi-stage runs**

```
-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2 -Dzookeeper.test.delayStages=SERVER_RECV,PREP,COMMIT,FINAL
```

The Set ensures duplicates are removed, e.g. `SERVER_RECV,SERVER_RECV,COMMIT` is the same as `SERVER_RECV,COMMIT`.

Stage alone is meaningless – if the opcode is not configured in `delayPerOpcode` the stage filter passes but `DS` is `null` and the injector logs `NO_DELAY` and returns, i.e. opcode takes priority.

-- November 12, 2026 --

## 1️⃣ What you need to prove

Your research is about **how ZooKeeper behaves when a request is delayed** (the “delay‑injection” feature).  
The questions you must answer are:

| #   | Question you must answer                                                                                                            |
| --- | ----------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Does the client really wait for the server to reply, or can it “give up” early?                                                     |
| 2   | How long can a single client survive a **full‑pipeline** (all 22 stages) with **HEAVY** delays?                                     |
| 3   | How does **concurrency** (multiple clients) change the picture?                                                                     |
| 4   | Does changing the **delay category** (SLOW vs HEAVY vs NETWORK) or the **stage filter** change the client‑side behaviour?           |
| 5   | What is the smallest **session timeout** that still lets a 2‑second (or 30‑second) heavy delay finish without dropping the session? |
| 6   | Does the client’s retry/back‑off logic keep the session alive, or does it just reconnect after the drop?                            |

To answer all of the above you need a **matrix of test cases** that covers every combination of the variables above.  
Below is a **complete, reproducible matrix** together with the **exact client‑code changes** you must make so the tests are deterministic and the results are comparable.

---

## 2️⃣ Test‑case matrix

| #       | Test name                                                                                                                    | Server side configuration (what you pass to the JVM)                                                                                                       | Client side configuration (what you set in the notebook)                                                                                  | Why this case matters                                                                                                                                                                               |
| ------- | ---------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **T1**  | **Full‑pipeline – single client, HEAVY (2 min) on every stage**                                                              | `-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2` <br> **No** `-Dzookeeper.test.delayStages` (all 22 stages)                                             | `SESSION_TIMEOUT_S = 60 min` (or 30 min + 10 % margin) <br> `NUM_CLIENTS = 1` <br> `OPERATIONS_PER_CLIENT = 1` (single `getData`)         | Baseline – proves that the full 22‑stage pipeline with 2‑minute CPU‑burn per stage still lets a single client stay alive if the session timeout is large enough.                                    |
| **T2**  | **Full‑pipeline – single client, HEAVY but **tiny** session timeout**                                                        | Same as T1                                                                                                                                                 | `SESSION_TIMEOUT_S = 2 s`                                                                                                                 | Shows the **minimum** timeout that still survives a 2‑minute block (it will _not_ survive; you will see many “Connection dropped” messages).                                                        |
| **T3**  | **Full‑pipeline – single client, **SLOW** (category 1) on every stage**                                                      | `-Dzookeeper.test.delayPerOpcode=4:1:2,5:1:2,8:1:2` <br> **No** `-Dzookeeper.test.delayStages`                                                             | `SESSION_TIMEOUT_S = 10 s` (SLOW only sleeps 2 s per stage, so 22 × 2 s = 44 s; 10 s is too small – you will see drops; increase to 60 s) | Demonstrates that a **SLOW** delay still blocks the thread, but it releases the CPU, so the client can still send heart‑beats.                                                                      |
| **T4**  | **Full‑pipeline – single client, HEAVY **but only on one opcode** (e.g., `getData`)**                                        | `-Dzookeeper.test.delayPerOpcode=4:2:2` <br> `-Dzookeeper.test.delayStages=PREP` (only the PREP stage of opcode 4)                                         | `SESSION_TIMEOUT_S = 30 s`                                                                                                                | Shows the impact of _partial_ delay injection – the client only experiences a 2‑minute stall on `getData`.                                                                                          |
| **T5**  | **Full‑pipeline – single client, HEAVY on **all** opcodes **but** with a **stage filter** that excludes the final 5 stages** | `-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2` <br> `-Dzookeeper.test.delayStages=PREP,PREP_ENQUEUE,PREP,PREP_ENQUEUE,PREP` (only the first 5 stages) | `SESSION_TIMEOUT_S = 30 s`                                                                                                                | Demonstrates that the client can survive if the delay is applied only to a subset of stages.                                                                                                        |
| **T6**  | **Concurrent – 2 clients, each with HEAVY on all stages**                                                                    | Same as T1 (full‑pipeline)                                                                                                                                 | `NUM_CLIENTS = 2` <br> `OPERATIONS_PER_CLIENT = 1` (single request per client)                                                            | Shows contention: two clients fighting for the same server threads; you will see longer effective latency and possibly more drops if the session timeout is too small.                              |
| **T7**  | **Concurrent – 5 clients, each with **SLOW** (category 1) on all stages**                                                    | `-Dzookeeper.test.delayPerOpcode=4:1:2,5:1:2,8:1:2` (SLOW)                                                                                                 | `NUM_CLIENTS = 5` <br> `OPERATIONS_PER_CLIENT = 3`                                                                                        | Tests scalability when the delay is cheap (SLOW). Expect higher throughput, fewer drops, but still verify that the session timeout is large enough for the cumulative delay (5 × 22 × 2 s ≈ 220 s). |
| **T8**  | **Concurrent – 3 clients, **HEAVY** 30‑second delays (instead of 2 min)**                                                    | `-Dzookeeper.test.delayPerOpcode=4:3:2,5:3:2,8:3:2` (3 min per stage)                                                                                      | `SESSION_TIMEOUT_S = 2 min` (120 s) <br> `NUM_CLIENTS = 3`                                                                                | Tests the **upper bound** of delay length – the session must survive 3 min per stage × 22 stages ≈ 66 min total; you will see many drops unless you raise the timeout to > 66 min.                  |
| **T9**  | **Concurrent – 3 clients, **HEAVY** 2‑minute delays **but** with **exponential back‑off** retry in client**                  | Same as T1                                                                                                                                                 | `SESSION_TIMEOUT_S = 30 min` <br> **Add client‑side retry** (see section 3)                                                               | Verifies that a well‑tuned retry strategy can survive longer delays without the client giving up.                                                                                                   |
| **T10** | **Read‑only client vs. read‑write client**                                                                                   | Same as T1                                                                                                                                                 | **Read‑only**: `client = KazooClient(..., read_only=True)` <br> **Read‑write**: default client                                            | Verifies that the delay injection works the same for read‑only vs. read‑write paths (the heavy delay is applied on the **read** path as well).                                                      |
| **T11** | **Client using async API (`asyncio` + `kazoo.asyncio`)**                                                                     | Same as T1                                                                                                                                                 | Use `from kazoo.asyncio import Protocol` and `client = Protocol(hosts=…) .start()` <br> `await client.get(...)`                           | Shows whether the async client behaves differently (e.g., its own event‑loop may miss heart‑beats).                                                                                                 |
| **T12** | **Client with **no** retry logic (raw `client.get` only)**                                                                   | Same as T1                                                                                                                                                 | Remove any `try/except` / retry wrapper; let the exception propagate                                                                      | Demonstrates the _worst‑case_ – the client will give up after the first connection loss.                                                                                                            |

> **How to run them** – each test case can be a separate notebook cell or a separate script.  
> Keep the **server configuration** constant for a given test (i.e., do not mix T1 and T3 in the same run).  
> Record the **exact start‑time**, **end‑time**, **number of connection‑loss events**, and **total latency** for each request. This makes the results comparable across runs.

---

## 3️⃣ Required changes in the **client code**

Below are the **minimal, non‑intrusive modifications** you should apply to the notebook (or any Python client you write) so that every test case can be executed reliably.

### 3.1 Centralise the session‑timeout value

```python
# -------------------------------------------------
# 1️⃣  Global constants (place near the top of the notebook)
# -------------------------------------------------
SESSION_TIMEOUT_S   = 30.0      # 30 minutes – change per test as described in the table
CONNECTION_TIMEOUT_S = 60.0    # generous connect timeout
MAX_RETRY_ATTEMPTS   = 20      # how many times we will retry a failed operation
BASE_RETRY_DELAY     = 1.0     # seconds, will be multiplied by 2**attempt
MAX_RETRY_DELAY      = 30.0    # cap the back‑off (30 s)
```

_Why?_ All test cases reference `SESSION_TIMEOUT_S`. By keeping it in one place you can change it in a single line and the rest of the code automatically adapts.

### 3.2 Create a **retry‑aware** wrapper (`safe_op`)

Replace the existing `safe_op` (or create a new one) with the version below.  
It:

- catches `ConnectionLoss`, `SessionExpiredError`, `socket.timeout`, and any `KazooError`.
- retries with **exponential back‑off** (`delay * 2**attempt`), never exceeding `MAX_RETRY_DELAY`.
- gives up after `MAX_RETRY_ATTEMPTS` attempts and returns `None` (so the test can log the failure).

```python
import time
import logging
from kazoo.exceptions import ConnectionLoss, SessionExpiredError, KazooError

log = logging.getLogger(__name__)

def safe_op(client, client_id, operation, *args, **kwargs):
    """
    Execute a ZooKeeper operation with retry/back‑off.
    `operation` is a callable that expects the client and returns a result.
    """
    attempt = 0
    delay = BASE_RETRY_DELAY
    while attempt < MAX_RETRY_ATTEMPTS:
        try:
            return operation(client, *args, **kwargs)
        except (ConnectionLoss, SessionExpiredError, KazooError) as exc:
            log.warning(
                "[%s] operation failed (attempt %d/%d): %s",
                client_id, attempt + 1, MAX_RETRY_ATTEMPTS, exc
            )
            time.sleep(min(delay, MAX_RETRY_DELAY))
            delay *= 2          # exponential back‑off
            attempt += 1
    # final failure
    log.error("[%s] operation failed after %d attempts", client_id, attempt)
    return None
```

**How to use it** (replace every direct call in the test cells):

```python
# old:
# data, stat = client.get(path)

# new:
data, stat = safe_op(client, f"client_{i+1}", client.get, path)
```

Do the same for `set`, `create`, `delete`, `ensure_path`, etc.

### 3.3 Make the **client creation** function respect the global timeout

```python
def create_zk_client(client_id: str) -> kazoo.client.KazooClient | None:
    """
    Build a KazooClient with the global timeout values.
    Returns None if the client cannot be started after MAX_RETRY_ATTEMPTS.
    """
    max_tries = 10
    for attempt in range(max_tries):
        try:
            client = kazoo.client.KazooClient(
                hosts=ZOOKEEPER_HOSTS,
                timeout=SESSION_TIMEOUT_S,
                connection_timeout=CONNECTION_TIMEOUT_S,
                connection_retry=kazoo.retry.KazooRetry(
                    max_tries=0,          # we handle retries ourselves
                    delay=1.0,
                    backoff=2.0,
                    max_jitter=1.0,
                ),
                command_retry=kazoo.retry.KazooRetry(
                    max_tries=0,
                    delay=1.0,
                    backoff=2.0,
                    max_jitter=1.0,
                ),
            )
            client.start()
            if client.connected:
                log.info("[%s] Kazoo client connected (id=%s)", client_id, hex(client.client_id[0]))
                return client
        except (ConnectionLoss, SessionExpiredError) as exc:
            log.warning("[%s] connection attempt %d/%d failed: %s",
                        client_id, attempt + 1, max_tries, exc)
            if attempt < max_tries - 1:
                time.sleep(min(2 ** attempt, 10))
    log.error("[%s] failed to start after %d attempts", client_id, max_tries)
    return None
```

Now every test cell simply calls `client = create_zk_client(f"client_{i+1}")`.  
If a client cannot be created, the test logs the failure and moves on – this prevents a single flaky client from aborting the whole experiment.

### 3.4 Ensure **proper cleanup** (stop/close)

At the end of each client’s work (or in a `finally` block) add:

```python
try:
    # … your operations …
finally:
    if client:
        client.stop()
        client.close()
```

This guarantees that the background thread that sends heart‑beats is stopped, otherwise the session may stay “half‑open” and skew later tests.

### 3.5 Logging (optional but highly recommended)

Add a simple logger configuration at the top of the notebook:

```python
import logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(threadName)s %(message)s",
    datefmt="%H:%M:%S"
)
```

All `log.info / log.warning / log.error` statements in the `safe_op` wrapper and in the test cells will now appear in the notebook output, making it trivial to spot connection‑loss events.

---

## 4️⃣ How to interpret the results

| Observation                                                        | What it tells you                                                                                                                                                                  |
| ------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **No “Connection dropped” lines** and the latency is ≈ delay‑value | The session timeout is large enough; the client can survive the delay.                                                                                                             |
| **Frequent “Connection dropped”** (every 2‑3 s)                    | Session timeout is too small; either raise `SESSION_TIMEOUT_S` or shorten the delay.                                                                                               |
| **Latency spikes that grow with the number of concurrent clients** | Thread‑pool exhaustion on the server side; consider reducing `NUM_CLIENTS` or using a smaller delay category.                                                                      |
| **Retry count > 0** (you see log lines like “retry 1/20”)          | Your retry logic is active; verify that the back‑off values are reasonable (no > 30 s delay unless you deliberately test long waits).                                              |
| **Client process exits with an exception**                         | Either the client could not be created (wrong timeout) or the server rejected the connection because the session timed out. Check the server log for “maxSessionTimeout” warnings. |

---

## 4️⃣ Quick checklist before you start the full experiment

1. **Re‑build the Docker image** after you change `DelayInjector.java` (the heavy‑delay → seconds conversion).
   ```bash
   docker-compose build
   docker-compose up -d
   ```
2. **Set the server’s `maxSessionTimeout`** to at least the largest session timeout you will use (e.g., 30 min = 1800000 ms). Add it to `zoo.cfg` (or pass `-Dzookeeper.maxSessionTimeout=1800000` on the JVM line).
   ```bash
   # in docker‑compose.yml
   environment:
     ZOO_MAX_SESSION_TIMEOUT: "1800000"
   ```
3. **Pick a test case** from the matrix, adjust the constants in the notebook, and run it.
4. **Record**:
   - start‑time, end‑time, total latency per request, number of connection‑loss events, and the final `maxSessionTimeout` value used by the server (visible in the server log).
5. **Repeat** for each cell in the matrix; keep a spreadsheet so you can plot latency vs. concurrent clients, latency vs. delay duration, etc.

---

## 5️⃣ TL;DR – What you need to do

1. **Server side** – use the exact `-Dzookeeper.test.delayPerOpcode` and optional `-Dzookeeper.test.delayStages` flags that correspond to the test case you want to run.
2. **Client side** – set a **session timeout** that is **larger than the longest possible delay** (e.g., 30 min for the full‑pipeline heavy case).
3. **Add a retry wrapper (`safe_op`)** with exponential back‑off (see code in §3.2).
4. **Create clients via `create_zk_client`** so the global timeout and retry parameters are applied uniformly.
5. **Run the matrix of test cases** (T1 … T12) and record the metrics.

When you have completed the matrix, you will have a **complete, reproducible dataset** that answers every research question about how ZooKeeper’s delay‑injection feature influences client sessions, concurrency, and retry behaviour. Good luck, and feel free to ping me if you hit a snag while wiring the test matrix!
