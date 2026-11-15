# Delay Injector Coverage Analysis

## Current Injection Points

The following processors/stages currently call `DelayInjector.inject`:

1. **PrepRequestProcessor.processRequest** – PREP_ENQUEUE, per-request
2. **PrepRequestProcessor.pRequest** – PREP stage, per-request opcode-aware
3. **SyncRequestProcessor.processRequest** – SYNC_ENQUEUE, per-request
4. **SyncRequestProcessor.run loop** – SYNC stage, per-loop stage-level
5. **SyncRequestProcessor.run loop after dequeue** – SYNC stage, per-request opcode-aware
6. **FinalRequestProcessor.processRequest** – FINAL stage, per-request opcode-aware
7. **FinalRequestProcessor.processRequest** – GETDATA stage, per-request opcode-aware for `getData` (OpCode 4)
8. **FinalRequestProcessor.processRequest** – GETCHILDREN stage, per-request opcode-aware for `getChildren`/`list children` (OpCode 8)
9. **FinalRequestProcessor.sendResponse** – _NOT injected_ yet
10. **CommitProcessor.commit** – COMMIT stage, per-request opcode-aware
11. **CommitProcessor.processRequest** – COMMIT stage, per-request opcode-aware
12. **Leader.propose** – LEADER_PROPOSE stage, per-request opcode-aware
13. **Leader.commit** – LEADER_COMMIT stage, stage-only
14. **Leader.processAck** – ACK_PROC stage, stage-only
15. **LeaderRequestProcessor.processRequest** – LEADER stage, per-request opcode-aware
16. **ProposalRequestProcessor.processRequest** – PROPOSAL stage, per-request opcode-aware
17. **Follower.processPacket** – FOLLOWER_RECV stage, stage-only
18. **Observer.processPacket** – OBSERVER_RECV stage, stage-only
19. **Learner.request** – LEARNER stage, per-request opcode-aware
20. **Learner.writePacket** – LEARNER_SEND stage, stage-only, now enabled
21. **Learner.readPacket** – LEARNER_RECV stage, stage-only, now enabled
22. **LearnerHandler ACK_RECV** – ACK_RECV stage, stage-only
23. **ObserverRequestProcessor.processRequest** – OBSERVER stage, per-request opcode-aware
24. **ZooKeeperServer.processPacket** – _NOT injected_ yet, would be SERVER_RECV

## Changes Made for Safety

Previously delays were commented out because the old `DelayInjector` used a hardcoded 15-minute SLOW delay and caused servers to hang on startup. With the refactored spec parsing `TYPE:DURATION` and validation, delays are only applied when `zookeeper.test.delayStages` is explicitly set. The following previously commented injections are now re-enabled:

- `Learner.writePacket` – `DelayInjector.inject("LEARNER_SEND", ...)`
- `Learner.readPacket` – `DelayInjector.inject("LEARNER_RECV", ...)`

## Per-request vs Per-loop

`SyncRequestProcessor` previously injected `SYNC` in the polling loop only. This has been changed to be per-request:

- Injection at `processRequest` for enqueue → `SYNC_ENQUEUE` per-request
- Injection in `run` after dequeuing `si` → `SYNC` per-request with opcode logging
  The loop-level injection before `poll` remains as stage-level back-pressure, but causal attribution now happens per request.

`PrepRequestProcessor` now also injects `PREP_ENQUEUE` per-request in `processRequest`.

## Gaps – Where injection is still missing or would be valuable

### Server entry/exit

- `ZooKeeperServer.processPacket` – SERVER_RECV (opcode-aware for getData/setData/getChildren)
- `FinalRequestProcessor` just before `cnxn.sendResponse` – FINAL_SEND

### Client side

Delay injection is server-only. Client-side `ClientCnxn.sendPacket` would require changes in `zookeeper-client`.

## Where injection is not needed

- Purely internal bookkeeping stages without request context e.g., `Leader.commit(long zxid)` without Request – stage-level delay is fine, opcode will be UNKNOWN.
- Polling loops without request context – stage-level delay is acceptable as back-pressure simulation.

## Recommended additions for full client→response coverage

## Supported Delay Stage Values

The `DelayInjector` now uses per-opcode custom delays via a single property.

**System property**

- `-Dzookeeper.test.delayPerOpcode=opcode:category:minutes,opcode:category:minutes`

Each entry maps an opcode integer to a delay category and duration. If the property is not set or empty, **no delays are injected** – default is safe/zero overhead.

| Category | Name    | Meaning                                  |
| -------- | ------- | ---------------------------------------- |
| `1`      | SLOW    | `TimeUnit.MINUTES.sleep(duration)`       |
| `2`      | HEAVY   | CPU burn busy-loop for duration          |
| `3`      | NETWORK | Sleep + thread rename to `-NETWORK-WAIT` |

**Per-opcode example**

```bash
-Dzookeeper.test.delayPerOpcode=4:1:15,5:2:5,8:1:10
```

- Opcode `4` = `getData` → category `1` SLOW for 15 minutes
- Opcode `5` = `setData` → category `2` HEAVY for 5 minutes
- Opcode `8` = `getChildren` (list children) → category `1` SLOW for 10 minutes

Only requests whose opcode matches an entry are delayed, and each opcode can have its own category and duration independently. All other opcodes pass through with zero overhead.

> 💡 **Important**: `delayPerOpcode` replaces the previous `delayStages` + `delayOpCodes` pair. Delay categories are numeric `1/2/3`. Stage-only injections without a `Request` are skipped.

1. `ZooKeeperServer.processPacket` – SERVER_RECV
2. `FinalRequestProcessor` just before `cnxn.sendResponse` – FINAL_SEND

With current changes the causal chain is:
Client send → SERVER_RECV [*] → PREP_ENQUEUE → PREP → SYNC_ENQUEUE → SYNC → ... → COMMIT → FINAL → FINAL_SEND [*] → Client receive
and each stage emits `ZK_OPCODE_LOG [STAGE][opcode=...]` plus existing `Request` BEFORE/AFTER logs.

No — there are **no direct delays on ping**. Your logs prove it.

Your delay injection is configured for:

- opcode 4 (getData)
- opcode 5 (setData)
- opcode 8 (getChildren)

Ping is a separate opcode and shows `duration=0` in your logs. Here are the two distinct delay mechanisms at play:

## 1. Explicit Delay Injection (Configured)

Your `-Dzookeeper.test.delayPerOpcode=4:2:2,5:2:2,8:2:2` triggers:

```
[SERVER_RECV][opcode=getData] HEAVY delay (2) minutes → starting
[SERVER_RECV][opcode=getData] HEAVY delay (2) minutes → CPU burn complete
```

The server **intentionally** blocks its NIO worker thread for 2 minutes per stage for getData/setData/getChildren.

## 2. Implicit Delay (Side Effect) — What You're Seeing

During that 2-minute CPU burn on opcode 4/5/8, the server thread is **100% monopolized**. Nothing else can be processed on that thread, including:

```log
opcode=ping phase=BEFORE duration=0   # arrives, but thread is busy
opcode=ping phase=AFTER  duration=0   # processed later
```

The ping itself isn't delayed (duration=0 confirms this), but **processing latency** increases because the server thread handling your connection is busy burning CPU on the getData request. The ping request gets queued behind the delayed operation.

## Why Connections Drop

Kazoo's heartbeat mechanism expects responses to its pings within a few seconds. If your client sends a getData request, the server starts a 2-minute CPU burn, and the client immediately sends a ping — the server still has that same thread busy burning CPU and can't respond to the ping in time.

The ping opcode itself is never delayed, but the **server's ability to respond to pings is blocked** by the delayed getData/setData operations happening on the same worker threads.

## Summary

| Delay Type             | Opcode | Duration    | Location                              |
| ---------------------- | ------ | ----------- | ------------------------------------- |
| Explicit (configured)  | 4,5,8  | 2 min HEAVY | FinalRequestProcessor.processRequest  |
| Implicit (side effect) | All    | Variable    | Thread pool exhaustion from CPU burns |

Ping operations remain fast individually, but their **end-to-end latency** suffers when server threads are busy.

Because ZooKeeper processes requests from a single client connection **in order**, and HEAVY delays monopolize CPU cores system-wide.

## ZooKeeper Request Processing Model

1. **Single connection = single session** — All requests from client `sessionID=216172782492450816` arrive on the same TCP socket
2. **Sequential processing** — Requests from the same session are processed in order to guarantee consistency. If getData is being delayed, subsequent requests from that session (including pings) queue up behind it
3. **Shared thread pool** — `FinalRequestProcessor` uses a limited set of worker threads. When one thread burns CPU on a HEAVY delay, other threads compete for remaining CPU cycles

## Why Your Ping Suffers

Your HEAVY delay is a **busy-wait loop**, not a sleep:

```java
// DelayInjector.java lines 162-168
while (System.nanoTime() - started < 0.5 * 60 * 1e9 * delayMinutes) {
    // No sleep — just burn CPU cycles
}
```

This means:

- **Thread blocked** — The thread processing getData can't do anything else
- **CPU monopolized** — 100% of one CPU core consumed by the spin loop
- **System-wide contention** — Other threads (including those handling pings) compete for remaining CPU cores and get scheduled much later

Your server log shows:

```
[SERVER_RECV][opcode=getData] HEAVY delay (2) minutes → starting
```

During those 2 minutes, that server thread is stuck. If your client sends a ping 5 seconds later, that ping might arrive on the same thread (due to sequential session processing) or on a different thread that's now CPU-starved by the burning thread.

## Key Difference: SLOW vs HEAVY

- **Category 1 (SLOW)**: `TimeUnit.MINUTES.sleep(delayMinutes)` — releases CPU, thread parks, other work can proceed
- **Category 2 (HEAVY)**: busy-loop — consumes CPU, starves other threads, system-wide impact

## Solution

Reduce CPU contention by either:

1. **Use SLOW instead of HEAVY**: `-Dzookeeper.test.delayPerOpcode=4:1:2,5:1:2,8:1:2`
2. **Reduce HEAVY duration**: `-Dzookeeper.test.delayPerOpcode=4:2:0.5,5:2:0.5,8:2:0.5` (30s instead of 120s)
3. **Add more server CPU cores** to accommodate the burns

The ping is not being intentionally delayed, but it's being **starved of CPU** by the aggressive HEAVY delays on your data operations.
