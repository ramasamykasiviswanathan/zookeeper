# Implementation Summary

## 1. Current Implementation Review
- `DelayInjector.java` originally had spec parsing commented out and used hardcoded `SLOW` type with 15 minute duration.
- Delay injection was called at various stages: FINAL, PREP, SYNC, COMMIT, LEADER_PROPOSE, LEADER, LEARNER, OBSERVER, PROPOSAL, etc.
- Causal logs are recorded via `Request.logDagMethodStart/End` and `ZK_OPCODE_LOG` entries.

## 2. OpCodes Identified
All ZooKeeper opcodes from `ZooDefs.OpCode`:
notification, create, delete, exists, getData, setData, getACL, setACL, getChildren, sync, ping, getChildren2, check, multi, create2, reconfig, checkWatches, removeWatches, createContainer, deleteContainer, createTTL, multiRead, auth, setWatches, sasl, getEphemerals, getAllChildrenNumber, setWatches2, addWatch, whoAmI, createSession, closeSession, error

See OPCODES_LIST.md for full list.

## 3. DelayInjector Modifications
- Restored spec parsing: `TYPE:DURATION` format (e.g., `SLOW:5`, `HEAVY:2`, `NETWORK:1`)
- Added overloads to accept `Request` and derive opcode via `Request.op2String(request.type)`
- Logs now include `[stage][opcode=...]` for causal tracking
- Added validation for empty spec, invalid format, negative duration
- Removed hardcoded duration/type

## 4. Call Site Updates
Updated the following processors to pass Request for opcode-aware logging:
- FinalRequestProcessor.processRequest
- PrepRequestProcessor.pRequest
- CommitProcessor.commit / processRequest
- Leader.propose
- LeaderRequestProcessor.processRequest
- Learner.request
- ObserverRequestProcessor.processRequest
- ProposalRequestProcessor.processRequest

## 5. Multiple Instances Setup
Docker Compose configuration in `docker-compose.yml` defines three ZooKeeper instances:
- zoo1 (myid=1, ports 2181/5005)
- zoo2 (myid=2, ports 2182/5006)
- zoo3 (myid=3, ports 2183/5007)

To run:
```bash
docker compose up --build
```
Set system property for delay testing:
```bash
-Dzookeeper.test.delayStages=SLOW:0
```

## 6. Validation
- Code compiles with `mvn -pl zookeeper-server -am compile`
- DelayInjector now logs with opcode: `ZK_OPCODE_LOG [FINAL][opcode=create] SLOW delay (0) minutes → starting`
- ZK_OPCODE_LOG entries from Request processing remain unchanged and can be correlated via selfId/traceId
- Grep logs for `ZK_OPCODE_LOG` to follow causal chain including delay injections

## 7. Causal Processing Tracking
- Delays are logged with same `ZK_OPCODE_LOG` prefix as request processing logs
- Opcode is included in delay log to allow per-opcode analysis
- Existing `Request.logDagMethodStart/End` provides BEFORE/AFTER with threadId, selfId, parentId, opcode, duration
- Combine delay logs with request logs to attribute latency per stage and opcode
