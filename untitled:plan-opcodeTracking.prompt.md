# Opcode Tracking Plan — Client to Completion

## Goal
Track every opcode from client request send through server receipt, prep/sync/final processing, quorum/leader-follower communication, storage/deletion, and response back.

## Log Pattern (ZK_OPCODE_LOG)
- `threadId`, `threadName`, `threadState`
- `selfId` (traceId), `parentId` (parentTraceId)
- `opcode`, `phase` (BEFORE/AFTER/METHOD_ENTER/METHOD_EXIT/THREAD_TRANSITION)
- `clientId`, `cxid`, `zxid`, `sessionID`
- `callerInfo` (class.method(file:line))
- `host`, `duration`

## Key Points
- Client: `ClientCnxn.sendPacket()` — log ENTER/EXIT with traceId
- Server entry: `ZooKeeperServer.processPacket()` — log BEFORE
- Prep/Sync/Final: `Request.logOpCodeDetails()` already logs BEFORE/AFTER
- Quorum: `LeaderRequestProcessor`, `FollowerRequestProcessor`, `CommitProcessor` — add `ZK_OPCODE_LOG` using `request.getTraceId()`
- Method-level: `trackMethodEnter/Exit()` inside `applyRequest()` / `pRequest()` for storage/deletion methods
- End-to-end: grep `ZK_OPCODE_LOG` by `selfId` to follow one request across threads
