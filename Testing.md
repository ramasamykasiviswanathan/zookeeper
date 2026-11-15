**Status: supplementary reference**

The authoritative test specification is:

- `/Users/ramasamykasiviswanathan/Source_Code/zooKeeper3_9/TIMEOUT_CAUSAL_TRACE_TEST_PLAN.md`

This file supplements the plan with a condensed test-case matrix. All durations are in **seconds** (the `DelayInjector` unit). The old “minutes” claims in earlier versions of this document are incorrect.

Key implementation facts:

- `DelayInjector` parses `-Dzookeeper.test.delayPerOpcode=<opcode>:<category>:<seconds>` where category `1=SLOW` (sleep), `2=HEAVY` (busy-loop), `3=NETWORK`.
- Only **14 request-aware stages** can actually delay a configured opcode. The other 8 declared stage names have no `Request`/opcode and are skipped. No single request traverses all 14 effective stages.
- The delay value is cast to `(long)` seconds, so the minimum meaningful `SLOW` delay is **1 second** (e.g. `0.5` → `sleep(0)` → no delay).
- Properties are read in a static initializer → **restart every server** after changing them.

Effective request-aware stage list:

```text
SERVER_RECV,LEADER,PREP_ENQUEUE,PREP,PROPOSAL,LEADER_PROPOSE,SYNC_ENQUEUE,SYNC_0,COMMIT,FINAL,GETDATA,GETCHILDREN,LEARNER,OBSERVER
```

Notebooks:

- `/Users/ramasamykasiviswanathan/Source_Code/Python_notebook/Concurrency_test_new.ipynb` — original single/concurrent/many/baseline scenarios.
- `/Users/ramasamykasiviswanathan/Source_Code/Python_notebook/Concurrent_Slow_Processing_Test.ipynb` — new notebook purpose-built for **concurrent multi-client slow-processing** tests with barrier-released overlapping requests.

Below you will find:

1. **A condensed test‑case matrix** – sub-minute (fraction-of-a-minute) slow-injection runs.
2. **Client‑code changes** – minimal edits to drive each test case.
3. **Server‑side configuration changes** – JVM system properties in `docker‑compose.yml`.
4. **Key concepts** (session timeout, causal tracing, retry policy).

---

## 1️⃣ Test‑case matrix (sub-minute slow injection)

All runs use **category `1` (SLOW)** so the server thread sleeps and releases CPU. Durations are in seconds.

| Test ID | Description | `ZOO_DELAY_PER_OPCODE` | `ZOO_DELAY_STAGES` | `OPERATION_SEQUENCE` | `SESSION_TIMEOUT_S` | `NUM_CLIENTS` | `OPS_PER_CLIENT` | Expected added latency |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **T1** | Baseline – no delay | (empty) | (empty) | `("get",)` | 60 | 1 | 1 | ~0 s |
| **T2** | Single client, entry delay only (`SERVER_RECV`) | `5:1:5` | `SERVER_RECV` | `("set",)` | 60 | 1 | 1 | ~5 s |
| **T3** | Single client, pre-response delay only (`FINAL`) | `5:1:5` | `FINAL` | `("set",)` | 60 | 1 | 1 | ~5 s |
| **T4** | Single client, quorum propose only (`LEADER_PROPOSE`) | `5:1:5` | `LEADER_PROPOSE` | `("set",)` | 60 | 1 | 1 | ~5 s |
| **T5** | Single client, sync + commit | `5:1:5` | `SYNC_0,COMMIT` | `("set",)` | 60 | 1 | 1 | ~10 s |
| **T6** | Single client, full write pipeline | `5:1:5` | `SERVER_RECV,PREP,LEADER_PROPOSE,SYNC_0,COMMIT,FINAL` | `("set",)` | 60 | 1 | 1 | ~30 s |
| **T7** | Read vs. write contrast (read) | `4:1:5` | `GETDATA,FINAL` | `("get",)` | 60 | 1 | 1 | ~10 s |
| **T8** | Concurrent slow writes (3 clients) | `5:1:5` | `SERVER_RECV,LEADER_PROPOSE,COMMIT,FINAL` | `("set",)` | 60 | 3 | 1 | ~20 s + contention |
| **T9** | Concurrent many-ops (5 clients × 5 ops) | `5:1:3` | `FINAL` | `("set",)` | 60 | 5 | 5 | ~15 s/client |
| **T10** | Session timeout too small (should fail) | `5:1:5` | `FINAL` | `("set",)` | 3 | 1 | 1 | Session expires mid-delay → `SessionExpiredError` |

---

## 2️⃣ Client‑code changes (what to edit in the notebook)

Below are the **minimal edits** you should make in the notebook to drive each test case.  
All changes are **self‑contained** – you do **not** need to touch the ZooKeeper server source.

### 2.1 Global constants (place near the top of the notebook)

Edit these per test case. Keep `ENABLE_OPERATION_RETRIES = False` for primary measurements so a failed `setData` is not silently re-applied (ambiguous commit outcome).

```python
# -------------------------------------------------
#  Global timeout constants – change per test case
# -------------------------------------------------
SESSION_TIMEOUT_S      = 60.0      # seconds; must exceed worst-case injected delay
CONNECTION_START_TIMEOUT_S = 30.0  # bounds initial connection establishment
ENABLE_OPERATION_RETRIES   = False  # False = primary research mode
MAX_OPERATION_ATTEMPTS     = 1
RETRY_BACKOFF_S            = 1.0
```

### 2.2 Notebook changes for each test

| Test | Notebook change |
|------|-----------------|
| T1 (baseline) | `OPERATION_SEQUENCE = ("get",)`; no server delay |
| T2–T6 (single client slow) | `OPERATION_SEQUENCE = ("set",)`; `NUM_CLIENTS = 1`; `OPERATIONS_PER_CLIENT = 1` |
| T7 (read vs write) | `OPERATION_SEQUENCE = ("get",)` for read row; `("set",)` for write row |
| T8 (concurrent writes) | `OPERATION_SEQUENCE = ("set",)`; `NUM_CLIENTS = 3`; `OPERATIONS_PER_CLIENT = 1` |
| T9 (concurrent many-ops) | `OPERATION_SEQUENCE = ("set",)`; `NUM_CLIENTS = 5`; `OPERATIONS_PER_CLIENT = 5` |
| T10 (session timeout too small) | `SESSION_TIMEOUT_S = 3`; `OPERATION_SEQUENCE = ("set",)`; expect `SessionExpiredError` |

The `Concurrent_Slow_Processing_Test.ipynb` notebook already implements a `threading.Barrier` so all clients release measured operations simultaneously — use that notebook for T8/T9.

| **T1–T6, T8, T9** | Set `SESSION_TIMEOUT_S` to the value shown in the matrix.                                                                                                                                                                                                                            |
| **T6, T7, T8, T9**                  | Change `NUM_CLIENTS` and `OPERATIONS_PER_CLIENT` to the values in the matrix.                                                                                                                                                                                                        |
| **T10**                             | Set `SESSION_TIMEOUT_S = 3`; `OPERATION_SEQUENCE = ("set",)`; expect `SessionExpiredError` / `ConnectionLoss` in the logs.                                                                                                 |
| **All**                             | Keep the `logging.basicConfig` line – it already prints timestamps that make it easy to spot “Connection dropped” events.                                                                                                                                                            |

---

## 3️⃣ Server‑side configuration changes

`docker-compose.yml` already drives `SERVER_JVMFLAGS` from two host environment variables:

```yaml
SERVER_JVMFLAGS: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 \
                  -Dzookeeper.test.delayPerOpcode=${ZOO_DELAY_PER_OPCODE-} \
                  -Dzookeeper.test.delayStages=${ZOO_DELAY_STAGES-} \
                  -Dzookeeper.request_stale_latency_check=false"
```

Pass per-test values via the host shell:

| Test | Host command (example) |
|------|------------------------|
| T1 baseline | `ZOO_DELAY_PER_OPCODE='' ZOO_DELAY_STAGES='' docker compose up -d --force-recreate` |
| T2 entry delay | `ZOO_DELAY_PER_OPCODE='5:1:5' ZOO_DELAY_STAGES='SERVER_RECV' docker compose up -d --force-recreate` |
| T3 FINAL only | `ZOO_DELAY_PER_OPCODE='5:1:5' ZOO_DELAY_STAGES='FINAL' docker compose up -d --force-recreate` |
| T4 LEADER_PROPOSE | `ZOO_DELAY_PER_OPCODE='5:1:5' ZOO_DELAY_STAGES='LEADER_PROPOSE' docker compose up -d --force-recreate` |
| T5 sync+commit | `ZOO_DELAY_PER_OPCODE='5:1:5' ZOO_DELAY_STAGES='SYNC_0,COMMIT' docker compose up -d --force-recreate` |
| T6 full pipeline | `ZOO_DELAY_PER_OPCODE='5:1:5' ZOO_DELAY_STAGES='SERVER_RECV,PREP,LEADER_PROPOSE,SYNC_0,COMMIT,FINAL' docker compose up -d --force-recreate` |
| T7 read contrast | `ZOO_DELAY_PER_OPCODE='4:1:5' ZOO_DELAY_STAGES='GETDATA,FINAL' docker compose up -d --force-recreate` |
| T8 concurrent writes | `ZOO_DELAY_PER_OPCODE='5:1:5' ZOO_DELAY_STAGES='SERVER_RECV,LEADER_PROPOSE,COMMIT,FINAL' docker compose up -d --force-recreate` |
| T9 concurrent many-ops | `ZOO_DELAY_PER_OPCODE='5:1:3' ZOO_DELAY_STAGES='FINAL' docker compose up -d --force-recreate` |
| T10 session expiry | `ZOO_DELAY_PER_OPCODE='5:1:5' ZOO_DELAY_STAGES='FINAL' docker compose up -d --force-recreate` |

**Restart every server** after changing these variables because `DelayInjector` reads them during static initialization:

```bash
docker compose restart
```

Server logs are bind-mounted from `./logs/zoo1/` … `./logs/zoo5/` (host paths) to `/logs` (container path). Grep them for `ZK_OPCODE_LOG` to confirm each stage fired:

```bash
grep -E 'ZK_OPCODE_LOG.*opcode=setData' ./logs/zoo1/zookeeper-*.log
```

### 3.2 Verifying the server side

After restarting, watch the server log:

```bash
docker logs -f zoo1 | grep -i ZK_OPCODE_LOG
```

You should see lines similar to:

```
ZK_OPCODE_LOG [FINAL][opcode=setData][opcodeNum=5] SLOW delay (5.0) seconds → starting
```

---

## 4️⃣ How to run the matrix

1. **Pick a test case** from the matrix (e.g. T3 – single client, SLOW 5 s delay at `FINAL`).
2. **Set the host env vars** and restart the ensemble:
   ```bash
   ZOO_DELAY_PER_OPCODE='5:1:5' ZOO_DELAY_STAGES='FINAL' docker compose up -d --force-recreate
   docker compose restart
   ```
3. **Edit the notebook constants** (`SESSION_TIMEOUT_S`, `NUM_CLIENTS`, `OPERATIONS_PER_CLIENT`, `ENABLE_OPERATION_RETRIES`) per the matrix.
4. **Run the notebook cells** in order.
5. **Inspect the logs** (client notebook output + server logs in `./logs/zoo1/`, `./logs/zoo2/`, `./logs/zoo3/`):
   - “Connection dropped” / “Session has expired” – indicates `SESSION_TIMEOUT_S` too small.
   - “ZK_OPCODE_LOG” entries – confirm delay was injected at the expected stage and opcode.
   - Retry attempts – verify the exponential back‑off (`time.sleep(min(30 * (2 ** attempt), 300))`).
6. **Record metrics** (total latency per request, reconnect count, retry count, success/failure count).
7. **Repeat** for each matrix cell; archive `zk_concurrent_slow_results/<RUN_ID>/` plus matching server logs under the same run ID.

You can script the whole flow (e.g., a bash loop that edits the notebook constants, rebuilds the Docker image, and runs the notebook via `jupyter nbconvert --to notebook --execute`) if you want to automate the full matrix.

---

## 5️⃣ Final notes & gotchas

| Issue                                   | What to watch for                                                                                     | Remedy                                                                                                                                      |
| --------------------------------------- | ----------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| **Server runs out of file descriptors** | HEAVY busy-loops monopolize CPU; too many concurrent clients can exhaust threads. | Keep concurrent clients modest (≤ 5‑10); prefer SLOW mode; increase OS `ulimit -n` if needed.                                                                  |
| **Session timeout too low**             | Sessions expire mid-delay when `SESSION_TIMEOUT_S` < total injected delay.     | Always set `SESSION_TIMEOUT_S` **≥** the worst-case total delay plus margin (use ≥ 60 s for the matrix).                                                      |
| **Clock drift**                         | The `HEAVY` busy-loop can drift under heavy host load.                          | Use **SLOW** mode (sleep) for deterministic timing; run on a low-load machine.                                                                                |
| **Async client deadlock**               | The async event‑loop can miss heartbeats if the main thread blocks.             | Avoid `time.sleep`; use `asyncio.sleep` and keep the loop responsive.                                                                                         |
| **Log truncation**                      | Very long logs may be truncated in the UI.                                      | Logs are bind-mounted to `./logs/zooX/`; grep the host files directly instead of relying on `docker logs`.                                                    |
| **Sub-second SLOW does nothing**        | `DelayInjector` casts duration to `(long)`, so `0.5` → `sleep(0)` → no delay.   | Use whole seconds (≥ 1) for `SLOW`.                                                                                                                           |

---

### 🎯 What you now have

- A **condensed sub-minute test matrix** (T1–T10) covering single-client, concurrent, and session-expiry cases.
- **Exact notebook edits** per test case (`OPERATION_SEQUENCE`, `NUM_CLIENTS`, `OPERATIONS_PER_CLIENT`, `SESSION_TIMEOUT_S`).
- **Server-side configuration** via `ZOO_DELAY_PER_OPCODE`/`ZOO_DELAY_STAGES` host env vars driving `SERVER_JVMFLAGS` in `docker-compose.yml`.
- Guidance on interpreting the logs (causal tracing via `ZK_OPCODE_LOG`, session-state messages, and host-side log files).

Run the matrix, collect the logs, and you’ll have a solid dataset to answer your research questions about **timeout behaviour**, **causal tracing**, and **concurrency** in Apache ZooKeeper.

If you need a concrete script to automate the whole pipeline (e.g., a Bash driver that flips the constants and re‑executes the notebook), just let me know and I’ll generate it for you. Happy testing!
