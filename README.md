# Apache ZooKeeper [![GitHub Actions CI][ciBadge]][ciLink] [![Travis CI][trBadge]][trLink] [![Maven Central][mcBadge]][mcLink] [![License][liBadge]][liLink]

<p align="left">
  <a href="https://zookeeper.apache.org/">
    <img src="https://zookeeper.apache.org/images/zookeeper_small.gif"" alt="https://zookeeper.apache.org/"><br/>
  </a>
</p>

For the latest information about Apache ZooKeeper, please visit our website at:

https://zookeeper.apache.org

and our wiki, at:

https://cwiki.apache.org/confluence/display/ZOOKEEPER

## Packaging/release artifacts

Either downloaded from https://zookeeper.apache.org/releases.html or
found in zookeeper-assembly/target directory after building the project with maven.

    apache-zookeeper-[version].tar.gz

        Contains all the source files which can be built by running:
        mvn clean install

        To generate an aggregated apidocs for zookeeper-server and zookeeper-jute:
        mvn javadoc:aggregate
        (generated files will be at target/site/apidocs)

    apache-zookeeper-[version]-bin.tar.gz

        Contains all the jar files required to run ZooKeeper
        Full documentation can also be found in the docs folder

As of version 3.5.5, the parent, zookeeper and zookeeper-jute artifacts
are deployed to the central repository after the release
is voted on and approved by the Apache ZooKeeper PMC:

https://repo1.maven.org/maven2/org/apache/zookeeper/zookeeper

## Java 8

If you are going to compile with Java 1.8, you should use a
recent release at u211 or above.

## ZK_OPCODE_LOG Tracing

ZooKeeper includes a detailed tracing mechanism for request processing that captures key metrics at each phase of request lifecycle.

### Log Format

Each log entry contains the following fields:

| Field        | Description                                               | Example                                                              |
| ------------ | --------------------------------------------------------- | -------------------------------------------------------------------- |
| `threadId`   | ID of the thread processing the request                   | `68`                                                                 |
| `selfId`     | Unique trace ID for this request (assigned at creation)   | `2`                                                                  |
| `parentId`   | ID of the parent request/phase (see chaining rules below) | `-2`                                                                 |
| `opcode`     | ZooKeeper operation code                                  | `createSession`, `getData`, `ping`                                   |
| `phase`      | Processing phase                                          | `BEFORE` or `AFTER`                                                  |
| `clientId`   | Client session ID                                         | `216178895678144512`                                                 |
| `cxid`       | Client transaction ID                                     | `5`                                                                  |
| `zxid`       | ZooKeeper transaction ID                                  | `0x100000001`                                                        |
| `sessionID`  | Session ID                                                | `216178895678144512`                                                 |
| `instr`      | Instruction count (memory proxy)                          | `22078976`                                                           |
| `time`       | Timestamp (milliseconds)                                  | `1788458693808`                                                      |
| `callerInfo` | Caller location: Class.method(file:line)                  | `PrepRequestProcessor.processRequest(PrepRequestProcessor.java:157)` |
| `host`       | Hostname of the server                                    | `zoo3`                                                               |
| `duration`   | Processing time in ms (0 for BEFORE)                      | `1`                                                                  |
| `instrDelta` | Instruction count delta since BEFORE                      | `156392`                                                             |

### Parent ID Chaining Rules

The `parentId` field chains related log entries:

| parentId   | Meaning                                                                     |
| ---------- | --------------------------------------------------------------------------- |
| `-1`       | Initial/default value - no prior request to chain from                      |
| `positive` | Reference to a **different** request's traceId (cross-request relationship) |
| `-selfId`  | Same request, links to its **previous phase** (BEFORE→AFTER chaining)       |

### Processing Chain Diagram

```
Request created (selfId=2, parentId=-1)
    │
    ├─► PrepRequestProcessor.BEFORE:  selfId=2, parentId=-1  ─┐
    │                                                       │ (pRequest() processing)
    └─► PrepRequestProcessor.AFTER:   selfId=2, parentId=-2 ─┘
                                                          │
                                                          │ (passes to next processor)
                                                          ▼
    ┌─► FinalRequestProcessor.BEFORE: selfId=2, parentId=2  ─┐
    │                                                       │ (applyRequest() processing)
    └─► FinalRequestProcessor.AFTER:  selfId=2, parentId=-2 ─┘
```

### Example Log Output

```
# createSession on zoo3
zoo3 | ZK_OPCODE_LOG threadId=68 selfId=2 parentId=-1 opcode=createSession phase=BEFORE clientId=216178895678144512 cxid=-2 zxid=-2 sessionID=216178895678144512 instr=22078976 time=1788458693808 callerInfo=PrepRequestProcessor.processRequest(PrepRequestProcessor.java:157) host=zoo3 duration=0
zoo3 | ZK_OPCODE_LOG threadId=68 selfId=2 parentId=-2 opcode=createSession phase=AFTER clientId=216178895678144512 cxid=-2 zxid=4294967297 sessionID=216178895678144512 instr=22235368 time=1788458693809 callerInfo=PrepRequestProcessor.processRequest(PrepRequestProcessor.java:162) host=zoo3 duration=1 instrDelta=156392

# Same session propagated to CommitProcessor on all servers (zoo1, zoo2, zoo3)
zoo1 | ZK_OPCODE_LOG threadId=60 selfId=2 parentId=2 opcode=createSession phase=BEFORE clientId=216178895678144512 cxid=-2 zxid=4294967297 sessionID=216178895678144512 instr=20733176 time=1788458693814 callerInfo=FinalRequestProcessor.processRequest(FinalRequestProcessor.java:159) host=zoo1 duration=0
zoo2 | ZK_OPCODE_LOG threadId=54 selfId=2 parentId=2 opcode=createSession phase=BEFORE clientId=216178895678144512 cxid=-2 zxid=4294967297 sessionID=216178895678144512 instr=20198280 time=1788458693814 callerInfo=FinalRequestProcessor.processRequest(FinalRequestProcessor.java:159) host=zoo2 duration=0
zoo3 | ZK_OPCODE_LOG threadId=66 selfId=2 parentId=2 opcode=createSession phase=BEFORE clientId=216178895678144512 cxid=-2 zxid=4294967297 sessionID=216178895678144512 instr=22235368 time=1788458693814 callerInfo=FinalRequestProcessor.processRequest(FinalRequestProcessor.java:159) host=zoo3 duration=0

# ping from zoo3
zoo3 | ZK_OPCODE_LOG threadId=68 selfId=3 parentId=-1 opcode=ping phase=BEFORE clientId=216178895678144512 cxid=-2 zxid=-3 sessionID=216178895678144512 instr=22777408 time=1788458697124 callerInfo=PrepRequestProcessor.processRequest(PrepRequestProcessor.java:157) host=zoo3 duration=0
zoo3 | ZK_OPCODE_LOG threadId=68 selfId=3 parentId=-3 opcode=ping phase=AFTER clientId=216178895678144512 cxid=-2 zxid=-3 sessionID=216178895678144512 instr=22777408 time=1788458697125 callerInfo=PrepRequestProcessor.processRequest(PrepRequestProcessor.java:162) host=zoo3 duration=1 instrDelta=0
```

### Key Observations from Example

1. **createSession on zoo3**: `parentId=-1` (BEFORE, new request) → `parentId=-2` (AFTER, same request), called from `PrepRequestProcessor.processRequest()`
2. **CommitProcessor propagation**: `parentId=2` on all servers indicates they received the same request (traceId=2) from the leader, called from `FinalRequestProcessor.processRequest()`
3. **ping on zoo3**: `parentId=-1` (BEFORE) → `parentId=-3` (AFTER, |parentId|=selfId=3 confirms same request), called from `PrepRequestProcessor.processRequest()`

### Correlation Rules

- To find the BEFORE that corresponds to an AFTER: look for `|parentId| = selfId` of the AFTER
- To find all related entries: match on `selfId` (same trace) or `sessionID` (same session)
- Positive `parentId` indicates cross-processor or cross-request relationship

# Contributing

We always welcome new contributors to the project! See [How to Contribute](https://cwiki.apache.org/confluence/display/ZOOKEEPER/HowToContribute) for details on how to submit patches as pull requests and other aspects of our contribution workflow.

[ciBadge]: https://github.com/apache/zookeeper/workflows/CI/badge.svg
[ciLink]: https://github.com/apache/zookeeper/actions
[liBadge]: https://img.shields.io/github/license/apache/zookeeper?color=282661
[liLink]: https://github.com/apache/zookeeper/blob/master/LICENSE.txt
[mcBadge]: https://img.shields.io/maven-central/v/org.apache.zookeeper/zookeeper
[mcLink]: https://zookeeper.apache.org/releases
[trBadge]: https://travis-ci.org/apache/zookeeper.svg?branch=master
[trLink]: https://travis-ci.org/apache/zookeeper
