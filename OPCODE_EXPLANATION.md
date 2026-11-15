# ZooKeeper OpCode Explanation

## 1. Opcode Usage – ZooDefs.OpCode

From `OPCODES_LIST.md` / `ZooDefs.OpCode`:

| Op | Code | Use |
|----|------|-----|
| **notification** | 0 | Internal server-to-client notification (watch events) |
| **create** | 1 | Create a persistent/ephemeral/sequential znode |
| **delete** | 2 | Delete a znode |
| **exists** | 3 | Test existence of a node and return its Stat |
| **getData** | 4 | Read data + Stat of a znode |
| **setData** | 5 | Overwrite data of a znode |
| **getACL** | 6 | Get ACL and Stat of a znode |
| **setACL** | 7 | Set ACL of a znode |
| **getChildren** | 8 | List immediate children of a node |
| **sync** | 9 | Synchronize the client's view with the leader |
| **ping** | 11 | Client keep-alive heartbeat |
| **getChildren2** | 12 | List children + Stat, no watch |
| **check** | 13 | Used inside MULTI to check version |
| **multi** | 14 | Atomic batch of multiple operations |
| **create2** | 15 | Create and return Stat |
| **reconfig** | 16 | Dynamic quorum reconfiguration |
| **checkWatches** | 17 | Internal watch checking |
| **removeWatches** | 18 | Internal watch removal |
| **createContainer** | 19 | Create a container node |
| **deleteContainer** | 20 | Delete a container node |
| **createTTL** | 21 | Create node with a TTL |
| **multiRead** | 22 | Read-only MULTI |
| **auth** | 100 | Client authentication |
| **setWatches** | 101 | Set watches on znodes |
| **sasl** | 102 | SASL authentication |
| **getEphemerals** | 103 | List ephemeral children under a prefix |
| **getAllChildrenNumber** | 104 | Count of all descendants |
| **setWatches2** | 105 | Set watches v2 |
| **addWatch** | 106 | Add a watch |
| **whoAmI** | 107 | Query session identity/ACL |
| **createSession** | -10 | Initial session creation request |
| **closeSession** | -11 | Close session |
| **error** | -1 | Server error response |

The three opcodes currently injected for latency testing:
* `GETDATA` = 4 – read data
* `SETDATA` = 5 – write data
* `GETCHILDREN` = 8 – read children list

## 2. Classification – Client vs Quorum/Internal Interaction

### Client-facing reads
Served locally and optionally forwarded to leader for linearizable reads.
* getData (4), getChildren (8), getChildren2 (12), exists (3), getACL (6), getEphemerals (103), getAllChildrenNumber (104), multiRead (22), sync (9)

### Client-facing writes – Quorum Consensus
Go through ZAB/Quorum consensus, need a majority.
* create (1), delete (2), setData (5), setACL (7), create2 (15), createTTL/Container/DeleteContainer (19-21), multi (14), reconfig (16), check (13)

### Session / Connection Control
Client <-> server handshakes.
* createSession (-10), closeSession (-11), ping (11), auth (100), sasl (102), whoAmI (107)

### Watch / Observation Internal
* setWatches (101), setWatches2 (105), addWatch (106), checkWatches (17), removeWatches (18), notification (0)

### Internal Quorum / Leader Operations
Quorum stages are not opcodes themselves but processing stages of an opcode that has passed through `ZooKeeperServer.processPacket` and `FinalRequestProcessor.processRequest`.

Stages currently instrumented with DelayInjector:
`PREP_ENQUEUE`, `PREP`, `SYNC_ENQUEUE`, `SYNC`, `LEADER_PROPOSE`, `LEADER`, `PROPOSAL`, `COMMIT`, `FINAL`, `FOLLOWER_RECV`, `OBSERVER_RECV`, `LEARNER`

## 3. Quorum Consensus With Non-Responsive Follower

ZooKeeper uses the ZooKeeper Atomic Broadcast – ZAB – protocol, majority-based.

* A write is committed only after the leader receives acks from a **majority** of voting servers, not all of them. For a 3-node ensemble the quorum is 2. Once leader + one follower ack, the proposal is committed and the client response is sent.
* The leader does **not** wait for every follower. Configuration controls timing:
  * `tickTime` – basic heart-beat interval, e.g. 2000 ms in `docker-entrypoint.sh`
  * `initLimit` – max ticks a follower will wait to initially sync with a leader
  * `syncLimit` – max ticks a follower will wait for a message / heartbeat from the leader
* If a follower does not respond:
  * The leader continues to serve writes as long as it can keep a majority. With one follower down in a 3-node set, leader + remaining follower = 2/3 quorum, writes proceed.
  * If quorum is lost, e.g. 2 out of 3 servers unreachable and leader cannot reach majority, leader steps down to LOOKING and new leader election occurs. Until a new majority is elected, writes block and clients see `CONNECTIONLOSS`/`SESSIONEXPIRED`. Reads can still be served by the connected server, but they are not linearizable.
  * Observers never count toward quorum; they can be down without affecting write availability.

### Where is the majority configured?

The quorum size is NOT a manual configuration value. It is derived automatically from the voting members configured in `zoo.cfg` via `server.X` entries.

* **Quorum definition:** `QuorumMaj` class in `org.apache.zookeeper.server.quorum.flexible.QuorumMaj`
* The constructor builds `votingMembers` from `allMembers` where `LearnerType.PARTICIPANT`:
  ```java
  half = votingMembers.size() / 2;
  ```
* Majority check:
  ```java
  public boolean containsQuorum(Set<Long> ackSet) {
      return (ackSet.size() > half);
  }
  ```
  A set is a quorum when `ackSet.size() > half`, i.e., strictly more than half the voting members.

* The voting view size is retrieved via:
  ```java
  public int getQuorumSize() {
      return getVotingView().size();
  }
  ```
  from `QuorumPeer`, where `getVotingView()` is the set of PARTICIPANT servers.

Thus the majority number = floor(voting_members / 2) + 1. It is computed at startup from the number of `server.X` entries with PARTICIPANT role; observers are stored in `observingMembers` and don't count toward the `half` calculation.

Example: 3 voting servers → `half = 3 / 2 = 1`, `containsQuorum` requires `ackSet.size() > 1` → need 2 acks.

The `SERVER_RECV` injection now ties `sessionId/cxid` to the subsequent `PREP → SYNC → LEADER/PROPOSAL → COMMIT → FINAL → FINAL_SEND` chain, which is exactly the path where the majority ack logic lives.
