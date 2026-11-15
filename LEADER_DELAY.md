

All changes are verified and complete. Everything compiles/parses cleanly. Here is the final answer to your three questions.

---

# 1. How to know which server is the leader in the log?

Your `LEADER.log` is a combined `docker compose logs` dump — every line is prefixed with the container name (`zoo1 |` … `zoo5 |`). The leader is the container printing the election-state marker. **In your current run, `zoo5` (myid=5) is the leader** — verified from the log itself.

| Grep marker | Meaning |
|---|---|
| `o.a.z.s.q.QuorumPeer - LEADING` | ✅ **Definitive: this container is the leader** |
| `o.a.z.s.q.Leader - LEADING - LEADER ELECTION TOOK - N MS` | Same, from `Leader.lead()` |
| `QuorumPeer - FOLLOWING` / `- OBSERVING` | follower / observer |
| `FastLeaderElection - ... n.state:LEADING, n.leader:5` | election message naming winner (`n.leader:5`) |
| `LearnerHandler - Follower sid: 3 : info : zoo3:2888:3888:participant` | **Only the leader logs this** (it accepts learners on 2888). In your log: zoo5 has 4 lines, zoo1–4 have 0 |

```bash
grep -E "QuorumPeer - (LEADING|FOLLOWING|OBSERVING)" LEADER.log
# live check from a client:
echo srvr | nc 127.0.0.1 2185   # → "Mode: leader"  (2185 = zoo5)
```

⚠️ The leader is **per-election, not fixed**. Every `docker compose up --force-recreate` (required after any delay change — `DelayInjector` reads its JVM props in a static initializer) can elect a different node, so re-check after each restart.

---

# 2. What to change to test **leader** delay injection

**The core problem:** with the notebook as-is, Kazoo pins to the *first* host (`2181` = zoo1, a follower), so leader-only stages (`LEADER`, `LEADER_PROPOSE`, `PROPOSAL`, `ACK_PROC`, `LEADER_COMMIT`) never fire for your requests. Two fixes were needed:

## A. Java fix — `Leader.java` `processAck()` (the one real bug)
The `ACK_PROC` injection was a **dead no-op** (2-arg `inject` path) and the `Proposal` lookup happened *after* the injection. I moved the lookup before it and pass the `Request` through so `ACK_PROC` logs/injects per-request opcode details:

```java
Proposal proposal = outstandingProposals.get(zxid);
DelayInjector.inject(
        "ACK_PROC",
        System.getProperty("zookeeper.test.delayStages", ""),
        proposal == null ? null : proposal.request);
```
✅ Verified: `mvn -pl zookeeper-server -am compile` → **EXIT=0**.

## B. Notebook fix — pin the client to the leader
Added a `PIN_TO_LEADER` block in the config cell that auto-detects the current leader from `docker compose logs` and overwrites `ZOOKEEPER_HOSTS` with just that host:

```python
PIN_TO_LEADER = True
LEADER_PORT_BY_CONTAINER = {"zoo1":2181, "zoo2":2182, "zoo3":2183, "zoo4":2184, "zoo5":2185}

def detect_leader_host():
    for container, port in LEADER_PORT_BY_CONTAINER.items():
        out = subprocess.run(["docker","compose","logs","--no-color",container],
                             capture_output=True, text=True, timeout=20).stdout
        role = None
        for line in out.splitlines():
            if "QuorumPeer - LEADING" in line:  role = "LEADING"
            elif "QuorumPeer - FOLLOWING" in line: role = "FOLLOWING"
            elif "QuorumPeer - OBSERVING" in line: role = "OBSERVING"
        if role == "LEADING":
            return f"127.0.0.1:{port}", container
    return None

if PIN_TO_LEADER:
    _found = detect_leader_host()
    if _found: ZOOKEEPER_HOSTS, LEADER_CONTAINER = _found
    else:      LEADER_CONTAINER = "unknown"
```
The last role banner wins, so a re-election after a restart is honored. The startup now prints:
```
Client targets: 127.0.0.1:2185  (leader container: zoo5)
```

**Run procedure for a leader-delay test:**
1. `docker compose up --build --force-recreate` (rebuild with the `Leader.java` change).
2. Re-detect the leader (the notebook does this automatically; or `grep -m1 "QuorumPeer - LEADING" logs/zoo*/*.log`).
3. Set the delay stages to **leader-only** ones in `docker-compose.yml`, e.g.
   `-Dzookeeper.test.delayStages=LEADER:150,LEADER_PROPOSE:150,PROPOSAL:150,ACK_PROC:150`
4. Set `SERVER_EXPECTATION = "leader_delayed"`, run the notebook, and confirm the `ZK_OPCODE_LOG` lines for that session show the injected `delay (...)` at the leader stages on the leader host only.

---

# 3. Changes for testing the **CRUD** operation

The `OPERATION_SEQUENCE` was already extended to `("get", "set", "children", "create", "delete")`. I added the missing `create`/`delete` branches:

**`invoke_operation`** (cell 6):
```python
if operation == "create":
    leaf = f"{path}/c{operation_index}"
    return client.create(leaf, f"{RUN_ID}:{operation_index}".encode("utf-8"))
if operation == "delete":
    leaf = f"{path}/c{operation_index}"
    return client.delete(leaf)
```

**`result_summary`** (cell 6):
```python
if operation == "create": return f"path={value}"
if operation == "delete": return "deleted"
```

Design notes:
- **`create`/`delete` use a per-index leaf** (`{path}/c{operation_index}`) under the client's base path, so repeated cycles and reused runs never hit `NODE_EXISTS`.
- **`create` precedes `delete` at the same index** in the default sequence, so the node always exists at delete time (delete of the leaf created at that index in the same cycle).
- The worker's setup (`ensure_path(root)` + `client.create(path, b"initial")`) is **outside the measured set** and tolerates `NODE_EXISTS` on rerun — unchanged, and compatible with the new CRUD handlers.
- Opcodes exercised: get(4), set(5), children(8), create(1), delete(2).

✅ **Final verification:** all code cells parse, `RUN_ID`/`operation_index` references all resolve, the Java change compiles, and the JSON notebook is valid.

---

## Summary of what I changed
| File | Change | Status |
|---|---|---|
| `Leader.java` `processAck()` | Moved `Proposal` lookup before injection; pass `Request` to `DelayInjector.inject("ACK_PROC", ...)` so the stage is a real, per-request op | ✅ compiles (EXIT=0) |
| `Concurrency_test_new.ipynb` config cell | Added `PIN_TO_LEADER` + `detect_leader_host()` to auto-pin the client to the current leader; prints the resolved leader | ✅ parses |
| `Concurrency_test_new.ipynb` `invoke_operation` | Added `create`/`delete` branches (per-index leaf, no collisions) | ✅ parses |
| `Concurrency_test_new.ipynb` `result_summary` | Added `create`/`delete` summary strings | ✅ parses |

No other code changes were required for leader delay injection — the `LEADER`, `LEADER_PROPOSE`, `PROPOSAL`, and `LEADER_COMMIT` call sites already exist in the quorum/processor chain; the only broken one was `ACK_PROC`, now fixed.