# Broker management notes — Phase 0 spike

Verified against the dev compose pair (`deploy/compose/compose.dev.yaml`):
`apache/activemq-artemis:2.44.0`, replication primary/backup, Jolokia on
`:8161` (primary) and `:8261` (backup), Core on `:61616` / `:61617`.
Credentials `artemis` / `artemis`, role `amq`.

Reproduce: `just up`, then the `curl` calls below, and
`ArtemisStudio` test `NotificationSpikeIT` for the notification catalogue
(`./mvnw test -Dtest=NotificationSpikeIT
-DargLine='-Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition'`).

All JSON below is verbatim tool output, only whitespace added.

---

## 1. Endpoint and MBean shapes

- Jolokia base: `http://<host>:8161/console/jolokia`
- Broker MBean: `org.apache.activemq.artemis:broker="primary"` — the quoted
  segment is `<name>` from `broker.xml`. URL-encoded that is
  `org.apache.activemq.artemis:broker=%22primary%22`.
- Per-queue MBean:
  `org.apache.activemq.artemis:address="DLQ",broker="primary",component=addresses,queue="DLQ",routing-type="anycast",subcomponent=queues`
- `--relax-jolokia` is already in the image's default `EXTRA_ARGS`, so Jolokia
  strict origin checking is **off** — no `Origin` header needed on requests. The
  old healthcheck sent one; it was noise and has been removed.
- Auth is HTTP Basic. Same creds as the broker (`artemis:artemis`).

### GET a single attribute

```
curl -su artemis:artemis \
  'http://localhost:8161/console/jolokia/read/org.apache.activemq.artemis:broker=%22primary%22/Active'
```
```json
{"request":{"mbean":"org.apache.activemq.artemis:broker=\"primary\"","attribute":"Active","type":"read"},"value":true,"status":200}
```

---

## 2. HA / replication attribute reads

`GET .../read/<broker-mbean>/Active,Started,Backup,HAPolicy,NodeID,Clustered,Version,ReplicaSync`

Primary (live), healthy pair:
```json
{
  "value": {
    "Active": true,
    "Started": true,
    "Version": "2.44.0",
    "ReplicaSync": true,
    "Backup": false,
    "HAPolicy": "Replication Primary w/quorum voting",
    "NodeID": "f7734597-a768-11f1-aa4c-ceae3fa2df1d",
    "Clustered": true
  },
  "status": 200
}
```

Backup (standing by), same pair:
```json
{
  "value": {
    "Active": false,
    "Started": true,
    "Version": "2.44.0",
    "ReplicaSync": true,
    "Backup": true,
    "HAPolicy": "Replication Backup w/quorum voting",
    "NodeID": "f7734597-a768-11f1-aa4c-ceae3fa2df1d",
    "Clustered": true
  },
  "status": 200
}
```

Semantics as observed:

| Attribute | Live primary | Standby backup | After failover (backup) | After failback |
|---|---|---|---|---|
| `Started` | `true` | `true` | `true` | `true` |
| `Active` | `true` | `false` | `true` | primary `true`, backup `false` |
| `Backup` | `false` | `true` | `false` | primary `false`, backup `true` |
| `ReplicaSync` | `true` | `true` | `false` (no replica) | `true` on both once resynced |
| `NodeID` | pair ID | **pair ID** (adopted from primary during sync) | unchanged | unchanged |
| `HAPolicy` | `Replication Primary w/quorum voting` | `Replication Backup w/quorum voting` | unchanged string | unchanged |

- **`Started` is the health signal**, not `Active`. A healthy backup is
  `Started=true` + `Active=false`. Both compose healthchecks read `Started`.
- **`NodeID` is shared across the pair.** A synced backup returns the primary's
  NodeID via `getNodeID()` even while passive. The survivor keeps that NodeID
  through failover and failback. → Studio should key a node/pair by `NodeID`, and
  treat "same NodeID on two connectors" as one logical node with a live and a
  backup endpoint, not two nodes.
- **`ReplicaSync` is the desync alarm.** `false` on a node that is `Backup=true`
  (or on a primary that should have a replica) = replication is not caught up.
- Split-brain check per non-negotiable #4: poll `Active` on every connector; two
  `Active=true` sharing a `NodeID` = split brain. Not observed in a clean
  stop/start cycle (see §6) — Artemis's quorum vote plus `allow-failback` handed
  primary back cleanly with no both-active window in the logs. `check-for-active-server`
  on the primary is what prevents the restarted primary from grabbing back while
  the backup is still live.

---

## 3. `listNetworkTopology()`

```
POST http://localhost:8161/console/jolokia
{"type":"exec","mbean":"org.apache.activemq.artemis:broker=\"primary\"","operation":"listNetworkTopology()"}
```
```json
{
  "request": {"mbean":"org.apache.activemq.artemis:broker=\"primary\"","type":"exec","operation":"listNetworkTopology()"},
  "value": "[{\"nodeID\":\"f7734597-a768-11f1-aa4c-ceae3fa2df1d\",\"live\":\"artemis-primary:61616\",\"primary\":\"artemis-primary:61616\",\"backup\":\"artemis-backup:61616\"}]",
  "status": 200
}
```

- **`value` is a JSON *string*** — parse it a second time. It is a JSON array,
  one object per logical node.
- Keys per node: `nodeID`, `live`, `primary`, `backup`. `live` and `primary`
  hold the same connector here. `backup` is absent when the pair has no backup
  (e.g. right after failover — see below).
- The connector values are **`host:port` of the `<connector>` the node
  advertises** — i.e. the compose service names `artemis-primary` /
  `artemis-backup`, resolvable only inside the compose network. This is exactly
  the ADR-0004 "nodes that advertise internal addresses need a manual override"
  case; it is the norm, not the exception. Studio's stored connector URL must be
  what it can actually reach, and discovery must not overwrite it.
- Backup returns the **same topology** as the primary.
- After the primary is stopped, the backup (now live) returns:
  ```json
  [{"nodeID":"f7734597-...","live":"artemis-backup:61616","primary":"artemis-backup:61616"}]
  ```
  — one node, `live`/`primary` now the backup's connector, no `backup` key.

---

## 4. `listQueues(options, page, pageSize)`

The README/roadmap wording `listQueues(filter, page, pageSize)` is loose. The
real signature is `listQueues(java.lang.String, int, int)` where the string is a
**JSON options document**, and the whole result `value` is again a **JSON
string**.

```
POST http://localhost:8161/console/jolokia
{"type":"exec",
 "mbean":"org.apache.activemq.artemis:broker=\"primary\"",
 "operation":"listQueues(java.lang.String,int,int)",
 "arguments":["{\"field\":\"name\",\"operation\":\"EQUALS\",\"value\":\"DLQ\"}", 1, 10]}
```
```json
{
  "value": "{\"data\":[{\"id\":\"3\",\"name\":\"DLQ\",\"address\":\"DLQ\",\"filter\":\"\",\"durable\":\"true\",\"paused\":\"false\",\"persistedPause\":\"false\",\"temporary\":\"false\",\"purgeOnNoConsumers\":\"false\",\"consumerCount\":\"0\",\"maxConsumers\":\"-1\",\"autoCreated\":\"false\",\"user\":\"\",\"routingType\":\"ANYCAST\",\"messagesAdded\":\"0\",\"messageCount\":\"0\",\"messagesAcked\":\"0\",\"messagesExpired\":\"0\",\"deliveringCount\":\"0\",\"messagesKilled\":\"0\",\"directDeliver\":\"false\",\"exclusive\":\"false\",\"lastValue\":\"false\",\"lastValueKey\":\"\",\"scheduledCount\":\"0\",\"groupRebalance\":\"false\",\"groupRebalancePauseDispatch\":\"false\",\"groupBuckets\":\"-1\",\"groupFirstKey\":\"\",\"enabled\":\"true\",\"ringSize\":\"-1\",\"consumersBeforeDispatch\":\"0\",\"delayBeforeDispatch\":\"-1\",\"autoDelete\":\"false\",\"internalQueue\":\"false\"}],\"count\":1}",
  "status": 200
}
```

### Options document

`{"field": <col>, "operation": <op>, "value": <str>, "sortColumn": <col>, "sortOrder": "asc"|"desc"}`

- All keys optional. `""` or `"arguments":["",1,50]` (empty options) → unfiltered.
- `operation`: `EQUALS`, `CONTAINS`, `NOT_CONTAINS`, `GREATER_THAN`, `LESS_THAN`
  (Artemis `ActiveMQAbstractControl` filter predicates). `EQUALS` and `CONTAINS`
  verified here.
- `field` is any returned column name, e.g. `name`, `address`, `consumerCount`,
  `messageCount`, `routingType`.

### Result envelope

- `{"data": [ {queue}, ... ], "count": <total matching, not page size>}`.
- `page` is **1-based**. `pageSize` caps `data` length.
- Out-of-range page → `{"data":[],"count":<total>}`, `status` still 200.
- Per-queue fields (2.44.0), **all values are strings** including numbers and
  booleans:
  `id, name, address, filter, durable, paused, persistedPause, temporary,
  purgeOnNoConsumers, consumerCount, maxConsumers, autoCreated, user, routingType,
  messagesAdded, messageCount, messagesAcked, messagesExpired, deliveringCount,
  messagesKilled, directDeliver, exclusive, lastValue, lastValueKey,
  scheduledCount, groupRebalance, groupRebalancePauseDispatch, groupBuckets,
  groupFirstKey, enabled, ringSize, consumersBeforeDispatch, delayBeforeDispatch,
  autoDelete, internalQueue`
- The Phase 2 queue view columns (routing type, depth, consumers, delivering,
  scheduled) map to `routingType`, `messageCount`, `consumerCount`,
  `deliveringCount`, `scheduledCount`.
- Internal queues (`internalQueue":"true"`, e.g. `$sys.mqtt.sessions`) come back
  in the list — filter them client-side or with a `name` predicate.

### Related list ops (same POST style, `value` is a JSON string)

- `listAddresses(java.lang.String)` with a separator arg → a plain separator-joined
  string, **not** JSON: `"$sys.mqtt.sessions,activemq.notifications,DLQ,ExpiryQueue"`.
  Prefer `listAddresses(options,page,pageSize)` (overload) for structured output.
- `listAllConsumersAsJSON()` → JSON string, array of
  `{consumerID, sequentialId, connectionID, sessionID, queueName, browseOnly,
  creationTime, deliveringCount, messagesInTransit, ...}`.
- Per-queue counters without the list: read the queue MBean directly, e.g.
  `.../read/<queue-mbean>/MessageCount,ConsumerCount,DeliveringCount,ScheduledCount,DurableMessageCount`
  → `{"MessageCount":0,"ConsumerCount":0,...}` (real numbers here, not strings).

---

## 5. Batched Jolokia POST  (ADR-0002 non-negotiable #1)

One POST, a JSON **array** of request objects, mixed `read` and `exec`:

```
POST http://localhost:8161/console/jolokia
[
 {"type":"read","mbean":"org.apache.activemq.artemis:broker=\"primary\"","attribute":["Active","ReplicaSync","NodeID"]},
 {"type":"exec","mbean":"org.apache.activemq.artemis:broker=\"primary\"","operation":"listNetworkTopology()"},
 {"type":"exec","mbean":"org.apache.activemq.artemis:broker=\"primary\"","operation":"listQueues(java.lang.String,int,int)","arguments":["",1,50]},
 {"type":"read","mbean":"org.apache.activemq.artemis:broker=\"primary\",name=\"NOPE\",component=queue-that-does-not-exist","attribute":"X"}
]
```

Response is an array, positionally aligned, **each entry independently
statused**:

```json
[
  {"request":{...,"type":"read"}, "value":{"Active":true,"ReplicaSync":true,"NodeID":"f7734597-..."}, "status":200},
  {"request":{...,"operation":"listNetworkTopology()"}, "value":"[{...}]", "status":200},
  {"request":{...,"operation":"listQueues(...)"}, "value":"{\"data\":[...],\"count\":4}", "status":200},
  {"request":{...}, "error_type":"javax.management.InstanceNotFoundException",
   "error":"javax.management.InstanceNotFoundException : org.apache.activemq.artemis:broker=\"primary\",name=\"NOPE\",...",
   "status":404}
]
```

- **Errors are isolated** — the bad entry returns `status:404` with `error` /
  `error_type`; the other three still return `200` + `value`. A batch is never
  poisoned by one bad entry.
- HTTP status of the whole call is `200` even when some entries failed. Studio
  must inspect per-entry `status`.
- No size ceiling hit at 4 entries + a 200-`pageSize` `listQueues`. Jolokia has
  no documented array-length limit; the practical cap is request/response body
  size and broker CPU for the batch. Plan: one batched POST per node per scrape
  tier (attrs + topology + one `listQueues` page), page large queue sets across
  successive scrapes.
- One POST per node satisfies the non-negotiable. `listNetworkTopology` and the
  HA attrs come back in the same POST as the queue page.

---

## 6. Failover / failback timeline

Clean pair, `NodeID f7734597-...`, both `ReplicaSync=true`.

1. **Cold sync** (backup start → announced): backup logs
   `AMQ221024 ... is synchronized with primary server, nodeID=f7734597-...`
   then `AMQ221031: backup announced` within ~1 s of the backup's HTTP server
   coming up. `ReplicaSync` reads `true` on both from then on.
2. **Kill primary** (`docker compose stop artemis-primary`): backup logs
   `AMQ221066: Initiating quorum vote: PrimaryFailoverQuorumVote` →
   `AMQ221083: ignoring quorum vote as max cluster size is 1` →
   `AMQ221071: Failing over based on quorum vote results` →
   `AMQ221007: Server is now active`. Elapsed ~0.6 s from vote to active.
   Backup then reads `Active=true, Backup=false, ReplicaSync=false`, and
   `listNetworkTopology()` collapses to the single self node.
   - `max cluster size is 1` because the dev pair has no third node to vote.
     A production cluster with more members runs a real quorum vote here.
3. **Restart primary** (`docker compose start artemis-primary`): backup logs
   `AMQ221025: Replication: sending ... to replica` (streams journal +
   bindings back to the returning primary) →
   `AMQ221039: Restarting as replicating backup server after primary restart` →
   `AMQ221024 ... synchronized` → `AMQ221031: backup announced`.
   Primary returns to `Active=true`, backup back to `Active=false, Backup=true`,
   `ReplicaSync=true` on both. **No window with both `Active=true`** appeared in
   either log. `allow-failback` on the backup is what makes it step down.
- Restart is a **cold resync** — the dev fixtures deliberately have no persistent
  journal volume (see §8), so a restarted primary streams the full state back
  from the live backup. Faithful for k8s (ephemeral pod disk); a shared-disk
  deployment would differ.

---

## 7. `activemq.notifications` catalogue

Consumed over the Core/JMS client (`artemis-jakarta-client`, already a
dependency). Full capture in `NotificationSpikeIT`. Provoked by: one JMS
connection, two sessions, an auto-created queue, a producer + consumer, one
message sent and received, then everything closed.

### `_AMQ_NotifType` values seen (13 messages)

| `_AMQ_NotifType` | Fired by | Extra headers on that message |
|---|---|---|
| `BINDING_ADDED` | queue/subscription bound | `_AMQ_Address`, `_AMQ_ClusterName`, `_AMQ_RoutingName`, `_AMQ_Binding_ID`, `_AMQ_Binding_Type`, `_AMQ_Distance` |
| `CONSUMER_CREATED` | consumer opened | `_AMQ_Address`, `_AMQ_ClusterName`, `_AMQ_RoutingName`, `_AMQ_ConsumerCount`, `_AMQ_ConsumerName`, `_AMQ_SessionName`, `_AMQ_RemoteAddress`, `_AMQ_User`, `_AMQ_ValidatedUser`, `_AMQ_CertSubjectDN`, `_AMQ_Distance` |
| `CONSUMER_CLOSED` | consumer closed | as `CONSUMER_CREATED` plus `_AMQ_FilterString`; `_AMQ_ConsumerCount` drops to `0` |
| `CONNECTION_CREATED` | connection opened | `_AMQ_ConnectionName`, `_AMQ_RemoteAddress`, `_AMQ_CertSubjectDN` |
| `CONNECTION_DESTROYED` | connection closed | `_AMQ_ConnectionName`, `_AMQ_RemoteAddress`, `_AMQ_CertSubjectDN` |
| `SESSION_CREATED` | session opened | `_AMQ_ConnectionName`, `_AMQ_SessionName`, `_AMQ_User`, `_AMQ_Protocol_Name` (`CORE`), `_AMQ_Client_ID`, `_AMQ_Distance` |
| `SESSION_CLOSED` | session closed | as `SESSION_CREATED` |
| `ADDRESS_ADDED` | address auto-created | `_AMQ_Address`, `_AMQ_Routing_Type` (int: `1`=ANYCAST) |
| `MESSAGE_DELIVERED` | message dispatched to a consumer (needs the plugin, see below) | `_AMQ_Address`, `_AMQ_RoutingName`, `_AMQ_ConsumerName`, `_AMQ_Message_ID`, `_AMQ_Routing_Type` |

### Headers on **every** notification

- `_AMQ_NotifType` — the type string above.
- `_AMQ_NotifTimestamp` — epoch millis (long).
- `_AMQ_Address` — present on most; the notification's own address for events
  that aren't address-scoped it is `activemq.notifications`.
- JMS envelope: `JMSMessageID` is **null** (notifications are core messages, not
  JMS-produced); `JMSXDeliveryCount = 1`. Read everything from the `_AMQ_*`
  properties, not JMS headers.
- `_AMQ_ClusterName` = `<routingName><nodeID>` concatenated (no separator) on
  binding/consumer events — carries the emitting node's NodeID as a suffix.

### Not seen in this run, but in the enum (`CoreNotificationType`, client 2.56)

`BINDING_REMOVED`, `BINDING_UPDATED`, `ADDRESS_REMOVED`, `CONSUMER_SLOW`,
`MESSAGE_EXPIRED`, `SECURITY_AUTHENTICATION_VIOLATION`,
`SECURITY_PERMISSION_VIOLATION`, `ACCEPTOR_STARTED`/`STOPPED`,
`BRIDGE_STARTED`/`STOPPED`, `CLUSTER_CONNECTION_STARTED`/`STOPPED`,
`DISCOVERY_GROUP_STARTED`/`STOPPED`, `BROADCAST_GROUP_STARTED`/`STOPPED`,
`PROPOSAL`, `PROPOSAL_RESPONSE`, `UNPROPOSAL`.
`*_REMOVED` did not fire because the auto-created address/queue was left in place
(`auto-delete-addresses=false`). The full enum is the catalogue to code against;
this run confirms the shape and the common headers.

### Broker config required for notifications

- `activemq.notifications` security-setting needs, for a JMS/Core subscriber,
  **`consume` AND `createNonDurableQueue`** (and `deleteNonDurableQueue` for a
  clean unsubscribe). `consume` alone → `AMQ229213 ... does not have
  permission='CREATE_NON_DURABLE_QUEUE' ... on address activemq.notifications`.
  Artemis matches the single most-specific `security-setting`, so that block must
  restate every permission the subscriber needs — it does not inherit from
  `match="#"`. Fixed in both dev `broker.xml` files.
- `CONNECTION_*` / `SESSION_*` / `MESSAGE_DELIVERED` / `MESSAGE_EXPIRED`
  notifications are **off by default**. They need
  `NotificationActiveMQServerPlugin` with `SEND_CONNECTION_NOTIFICATIONS`,
  `SEND_SESSION_NOTIFICATIONS`, `SEND_DELIVERED_NOTIFICATIONS`,
  `SEND_EXPIRED_NOTIFICATIONS` = `true`. Added to both dev `broker.xml` files.
  Without it only binding/consumer/address/security events are emitted — this
  is a `broker.xml` snippet the Phase 4 `NOTIFICATIONS` capability hint must
  show.

---

## 8. What needs the Core client (not doable over Jolokia)

- **Push.** Jolokia is request/response only. `activemq.notifications` is a
  subscription — consumer/session/connection/binding events in real time (Phase 4
  SSE fan-out, Phase 5 request-reply tracing) require the Core client consuming
  that address. Jolokia can only poll derived state.
- **Faithful message I/O.** Browsing/sending with real headers, properties and
  binary bodies (Phase 3/4) wants the Core client. Jolokia's
  `browse()` / `sendMessage()` management ops stringify and are clumsy for
  binary.
- Everything in §2–§5 (HA state, topology, queue/address/consumer enumeration,
  counters) is fine over batched Jolokia and needs no Core client.

### Core client gotcha found here

The broker pushes its **cluster topology** to every CORE client on connect, and
it advertises the `<connector>` hosts — here the compose service names
`artemis-primary` / `artemis-backup`. A client that cannot resolve those (any
process outside the compose network) logs `AMQ214033: Cannot resolve host` and
blocking calls can fail with `AMQ219016`. Mitigations for Studio's Phase 4 Core
client: run it on the broker network (the compose `studio` service already is),
and/or `useTopologyForLoadBalancing=false` + `reconnectAttempts=0` on the
`ActiveMQConnectionFactory`, and/or per-node manual connector overrides
(ADR-0004). The dev spike test sets the factory flags and still logs the resolve
error harmlessly because the topology is received before the flag is honoured;
functionally it is fine once the client isn't forced into a blocking retry.

---

## 9. Contradictions / notes against the ADRs

Nothing contradicts ADR-0002 or ADR-0004. Reinforcements:

- **ADR-0004** ("nodes that advertise internal addresses need a manual
  override") — confirmed as the *common* case, not the edge. `listNetworkTopology`
  returns the advertised `<connector>` `host:port` verbatim; in any containerised
  or NAT'd deployment that is not what Studio dials. The stored connector URL and
  `manual_override` must be first-class from day one (they already are in
  `002-estate.sql`).
- **ADR-0002 / non-negotiable #4** — `Active` polling for split-brain is sound.
  Add `NodeID` to the poll: the signal is *two `Active=true` sharing one
  `NodeID`*. `ReplicaSync=false` on a `Backup=true` node is a separate,
  lower-severity "replication desynced" alert (non-negotiable, and a built-in
  critical alert in Phase 7).
- **ADR-0002** capability model — `NOTIFICATIONS` is gated not just on the Core
  acceptor being reachable but on (a) `consume` + `createNonDurableQueue` on
  `activemq.notifications` and (b) `NotificationActiveMQServerPlugin` for the
  connection/session/message event classes. The capability probe should say
  which of the two is missing.

### Dev fixture changes made this session

- `deploy/compose/artemis/{primary,backup}/broker.xml` — rewritten as **complete**
  configs (the image `cp`s `etc-override/*` over the generated `broker.xml`, it
  does not XML-merge; the old fragments would have booted with no acceptors).
  Baseline from `artemis create` in image 2.44.0. Added: replication `ha-policy`,
  static `cluster-connection`, `NotificationActiveMQServerPlugin`,
  `activemq.notifications` create permissions. `max-disk-usage` raised to `98`
  **for dev only** — a laptop past 90% disk otherwise blocks every producer
  (`AMQ212054`).
- `deploy/compose/compose.dev.yaml` — backup healthcheck added (reads `Started`
  on `broker="backup"`), backup `depends_on` primary `service_healthy`, stray
  `Origin` header dropped from the primary healthcheck.
- **Image gotcha:** `docker-run.sh` copies `etc-override/*` only when creating the
  instance (`if ! [ -f ./etc/broker.xml ]`). A persisted
  `/var/lib/artemis-instance` volume makes later `broker.xml` edits silently
  ineffective. The dev stack keeps that path ephemeral on purpose; `just down`
  (which is `down -v`) is the way to pick up fixture edits.

---

## 10. Phase 2 surface checks

Verified against the same dev pair (image `2.44.0`, `:8161`) during Phase 2
planning. Seeding: 602 queues created via one batched `createQueue(address,
name, routingType)` POST across two addresses `SPIKE.A` / `SPIKE.B`; one
long-lived `artemis producer` + `artemis consumer` on `queue://SPIKE.A.q000`
for a live CORE connection/session/producer/consumer. Fixtures captured verbatim
under `src/test/resources/jolokia/`.

### The six list operations share one signature

`listQueues`, `listAddresses`, `listConsumers`, `listConnections`,
`listSessions`, `listProducers` all expose
`(java.lang.String options, int page, int pageSize)` and return `value` as a
JSON **string** wrapping `{"data":[…],"count":N}` — the same double-decode as
`listQueues`. `""` options → unfiltered. `listSessions` and `listAddresses`
also have a 1-arg overload; the GET URL form is ambiguous between them (400),
so **always POST with the explicit `(java.lang.String,int,int)` signature**.

Newly captured shapes (2.44.0), all scalars are JSON strings unless noted:

| Op | `count` here | Fields |
|---|---|---|
| `listSessions` | 4 | `id, user, validatedUser, creationTime, consumerCount, producerCount, connectionID, clientID` |
| `listConnections` | 5 | `connectionID, remoteAddress, users, creationTime, implementation, protocol, clientID, localAddress, sessionCount, proxyAddress, proxyProtocolVersion` |
| `listProducers` | 1 | `id, name, session, clientID, user, validatedUser, protocol, address, localAddress, remoteAddress, creationTime` (epoch millis string), `msgSent, msgSizeSent, lastProducedMessageID` |
| `listConsumers` (3-arg) | 1 | `id, session, clientID, user, validatedUser, protocol, queue, queueType, address, localAddress, remoteAddress, creationTime, status, filter, lastDeliveredTime, lastAcknowledgedTime, messagesDelivered, messagesDeliveredSize, messagesAcknowledged, messagesAcknowledgedAwaitingCommit, messagesInTransit, messagesInTransitSize` |

Fixtures: `list-sessions.json`, `list-connections.json`, `list-producers.json`,
`list-consumers.json`.

### Verdicts

| Check | Result | Consequence for Phase 2 |
|---|---|---|
| **`sortColumn` / `sortOrder` in the options doc** | **Not honoured.** `listQueues` with `{"sortColumn":"messageCount","sortOrder":"desc"}` returns `java.lang.NullPointerException: no mapping for field`, HTTP 500 (`list-queues-sorted.json`). | Studio must **not** sort at the broker. Tier B classifies hot vs idle queues Studio-side from the last `queue_snapshot`; grids sort in the aggregation/DB layer. ADR-0015's tier-B "hot page (sorted)" degrades to the fallback path as the primary path. |
| **`GREATER_THAN` predicate** | **Unverified.** Host disk was at 98.5% so the broker blocked every producer (`AMQ229119` / `AMQ212054`); all queues stayed at `messageCount 0`, so a `GREATER_THAN 0` filter returning `count 0` (`list-queues-gt.json`) is indistinguishable from "predicate ignored". | Treat numeric predicates as unavailable; classify Studio-side. Re-verify opportunistically on a machine with disk headroom, but the design does not depend on it. |
| **`field` / `operation` / `value` filter (`EQUALS`, `CONTAINS`)** | Works (Phase 0 §4, re-confirmed: `EQUALS` on `address` → `count 300`). | Safe to pass through for the grid text filter where a single-field predicate suffices. |
| **Batch / large page ceiling** | `listQueues` `pageSize=500` on a 603-queue broker → HTTP 200, 500 rows, ~438 KB raw. No error, no truncation beyond the page size (`list-queues-page-500.json`, trimmed to 25 rows in the repo). | One `listQueues` page of 200–500 per node per tick is fine. Page large sets across successive tier-C ticks as planned. |
| **Queue-name uniqueness per broker** | **Unique.** `createQueue("SPIKE.A","DUPQ",…)` succeeds; a second `createQueue("SPIKE.B","DUPQ",…)` fails with `IllegalStateException`. | `queue_snapshot` PK `(node_id, queue_name)` is safe — no changeset to widen the key with `address`. |

### Environment note

The host running this spike was disk-constrained (98.5% used), which
`max-disk-usage=98` in the dev `broker.xml` tolerates for boot but which blocks
all message production. Anything in Phase 2 that needs non-zero queue depths
(the manual acceptance pass, a re-check of numeric predicates) needs disk
headroom on the host. `./mvnw verify` (Testcontainers) also needs free disk.

---

## 11. Phase 3 surface checks — message operations

Verified against the same dev pair (`apache/activemq-artemis:2.44.0`, primary
`:8161`, Jolokia). Two anycast queues created for the run: `PHASE3.SRC` (source)
and `PHASE3.DST` (move target). Messages sent via the queue MBean's
`sendMessage(...)`. Fixtures captured verbatim under `src/test/resources/jolokia/`:
`browse.json`, `browse-truncated.json`, `browse-bad-filter.json`,
`count-messages.json`, `move-messages.json`, `remove-messages.json`,
`remove-all-messages.json`, `retry-messages.json`, `send-message.json`,
`address-settings.json`.

Queue MBean object name (composite, `::`-free — the `address` *field* in a
browsed row is `PHASE3.SRC::PHASE3.SRC`, the object name is not):

```
org.apache.activemq.artemis:address="PHASE3.SRC",broker="primary",component=addresses,queue="PHASE3.SRC",routing-type="anycast",subcomponent=queues
```

`BrokerMBeans.queue(brokerObjectName, address, queue, routingType)` already
produces exactly this.

### 11.1 Operation signatures (from the queue MBean's `list` metadata)

| Operation | Signature | Returns |
|---|---|---|
| browse (all) | `browse()` | `CompositeData[]` |
| browse (filter) | `browse(java.lang.String)` | `CompositeData[]` |
| browse (page) | `browse(int page, int pageSize)` | `CompositeData[]` |
| **browse (page + filter)** | `browse(int page, int pageSize, java.lang.String filter)` | `CompositeData[]` |
| count | `countMessages()` / `countMessages(java.lang.String filter)` | `long` |
| count grouped | `countMessages(java.lang.String filter, java.lang.String groupByProperty)` | JSON `String` |
| move by id | `moveMessage(long id, java.lang.String targetQueue[, boolean rejectDuplicates])` | `boolean` |
| move by filter | `moveMessages(java.lang.String filter, java.lang.String targetQueue[, boolean rejectDuplicates])` | `int` (count) |
| delete one | `removeMessage(long id)` | `boolean` |
| delete by filter | `removeMessages(java.lang.String filter)` | `int` (count) |
| purge | `removeAllMessages()` | `int` (count) |
| retry one | `retryMessage(long id)` | `boolean` |
| retry all | `retryMessages()` | `int` (count) |
| expire one | `expireMessage(long id)` | `boolean` |
| expire by filter | `expireMessages(java.lang.String filter)` | `int` (count) |
| send | `sendMessage(java.util.Map headers, int type, java.lang.String body, boolean durable, java.lang.String user, java.lang.String password)` | `String` (new message id) |

`getAddressSettingsAsJSON(java.lang.String match)` is on the **broker** MBean.

### 11.2 Verdict table

| Question | Answer | Consequence for Phase 3 |
|---|---|---|
| Is `browse()`'s `value` a JSON string (double-decode) or a real array? | **Real JSON array.** Unlike the six `list*` ops, `browse*` returns a `CompositeData[]` that Jolokia serialises directly — no second `JSON.parse`. `BrokerListOps`' double-decode does **not** apply; `MessageBrowser` reads `entry.value()` as an array node. | `MessageBrowser.decodeBrowse` iterates `value` directly. |
| Does `browse()` page server-side? | **Yes.** `browse(int page, int pageSize, String filter)` — **1-based** page, returns that slice. Verified: `browse(1,3,"")` → ids [78,84,90], `browse(2,3,"")` → [95,102,108]. There is also a hard server cap, `managementBrowsePageSize` (200 here, from `getAddressSettingsAsJSON`) — `browse()` never returns more than that regardless of `pageSize`. | Studio pages at the broker via `browse(page,size,filter)`; no Studio-side slicing needed. `artemis-studio.browse.max-rows` (task 2.5) is **not needed** — the broker already caps. Drop task 2.5 / the `browse.max-rows` setting. |
| Browsed-message field set, and which key holds the body? | Per row: `messageID` (String), `address` (`"ADDR::QUEUE"`), `type` (int; 3 = TEXT), `priority` (int), `durable` (bool), `expiration` (long), `timestamp` (long), `userID` (String, `""` when unset), `redelivered` (bool), `protocol` (`"CORE"`), `persistentSize` (int), `largeMessage` (bool), `PropertiesText` (String rendering), and eight typed property maps — `StringProperties`, `IntProperties`, `LongProperties`, `DoubleProperties`, `FloatProperties`, `ShortProperties`, `ByteProperties`, `BooleanProperties` (each `null` when empty). **Body:** `text` for `type == 3` (TEXT). Non-text bodies are not exercised here — Jolokia has no faithful bytes path (Phase 4). | `MessageSummaryView` / `MessageDetailView` fields map straight from this. `bodyPreview` = `text`. There is **no richer "detail" call** than `browse` — a single-message read is `browse(1, N, filter)` scanned for the `messageID` in memory; detail returns the same (still-truncated) `text`, just isolated. |
| How is body truncation signalled? | **A literal suffix `, + <N> more` appended to the truncated string value.** The oversized message (4000-char body) came back as `text` of length 269 ending `"…PPPP, + 3744 more"`. Visible prefix = `269 − len(", + 3744 more")` = **256** = the broker default `management-message-attribute-size-limit` on 2.44. Detection: the value matches `/, \+ \d+ more$/`. Approx original length = `visiblePrefix + N`. This applies to **any** attribute Jolokia returns (property values too), not just the body. | `MessageBrowser` sets `bodyTruncated = true` when `text` (or any property value) matches that suffix, and reports `truncationLimit ≈ text.length − suffix.length` from a truncated row. There is **no need to `- [ ]` a heuristic ceiling comment** — the `, + N more` marker is an explicit broker signal, not a guess. |
| Does `getAddressSettingsAsJSON` expose `managementMessageAttributeSizeLimit`? | **No.** `getAddressSettingsAsJSON("#")` returns `addressFullMessagePolicy, maxSizeBytes, maxReadPageBytes, maxReadPageMessages, pageLimitBytes, pageLimitMessages, maxSizeMessages, pageSizeBytes, messageCounterHistoryDayLimit, redeliveryDelay, deadLetterAddress, expiryAddress, slowConsumerThresholdMeasurementUnit, autoCreateQueues, autoDeleteQueues, autoCreateAddresses, autoDeleteAddresses, managementBrowsePageSize` — and **nothing** for the attribute-size limit. No broker MBean attribute or operation exposes it either (checked the full `list`). Its `value` **is** a double-encoded JSON string (unlike `browse`). | **`MESSAGE_BODY_FULL` cannot be probed.** It becomes *observed*, not probed: `UNKNOWN` until a browse actually returns a `, + N more` row, then `UNAVAILABLE` with the observed approximate limit + the `broker.xml` snippet. It can never be asserted `AVAILABLE` over Jolokia (you can't prove no message is truncated without reading them all). **This changes ADR-0021 and the `broker-capabilities` delta** — see §11.3. |
| Do `deadLetterAddress` / `expiryAddress` come from `getAddressSettingsAsJSON`? | **Yes** — `"DLQ"` and `"ExpiryQueue"` here, per-address (pass the address; `"#"` gives the default). | `DlqService` reads them from `getAddressSettingsAsJSON(address)` — no name heuristic, as designed (D9). |
| Do `moveMessages` / `removeMessages` / `retryMessages` / `expireMessages` return the affected count? | **Yes, the by-filter and bulk forms return `int` = the count.** Verified: `moveMessages("region='us'","PHASE3.DST")` → `1`; `removeMessages("orderId='A-1'")` → `1`; `removeAllMessages()` → `3`; `retryMessages()` → `0`. The **by-id** forms (`moveMessage`, `removeMessage`, `retryMessage`, `expireMessage`) return `boolean` per call. | Audit `affected_count` for a by-filter op = the broker's returned `int`. For a by-id op = the number of `true` results. |
| Does `sendMessage` return the new message id? | **Yes** — the id as a **String** (`"33"`, `"152"`, …). | `SendMessageResult` carries it; audit `affected_count = 1`. |
| Do message **filter** expressions support numeric predicates? | **Yes.** `countMessages("AMQSize > 1000")` → `1` correctly. This is a real Artemis *core filter expression* (`AMQSize`, `AMQPriority`, `AMQTimestamp`, `AMQExpiration`, `AMQUserID`, `AMQDurable`, plus any property name) — **not** the broken `list*` options `sortColumn` / `GREATER_THAN` from §10. Message-op filters are a different, working code path. | The by-filter UI can safely offer `>`, `<`, `=` on `AMQSize` / `AMQPriority` / `AMQTimestamp` and on property names. |
| Invalid filter behaviour? | `browse("this is not a filter ==")` → `status: 500`, `error_type: java.lang.IllegalStateException`, `error: "AMQ229020: Invalid filter: …"`. Per-entry status in a batch (§5) isolates it. | `MessageBrowser` maps an `AMQ229020` / `IllegalStateException` on a filter arg to a **400 `invalid-value`** problem (operator typo), not a 502 broker error. `ApiExceptionHandler` already maps `IllegalArgumentException` → 400 — throw that. |

### 11.3 `MESSAGE_BODY_FULL` — probed → observed (ADR-0021 correction)

The design assumed the broker's default `management-message-attribute-size-limit`
could be read and the capability set from it. It cannot (verdict table above).
The honest, implementable model:

- **`UNKNOWN` by default** — reason: *"Studio learns whether message bodies are
  truncated only by browsing; no message has been browsed yet."* Ships the
  `broker.xml` snippet regardless so the operator can pre-emptively raise the
  limit.
- **`UNAVAILABLE` once observed** — the first browse (any queue on the
  connection) that returns a `, + N more` value flips it, with reason *"the
  broker truncated a message body/property at ≈256 bytes; raise
  `management-message-attribute-size-limit`"* and the snippet. The observed limit
  is remembered on the (in-memory) capability the same way other probe results
  are.
- **Never `AVAILABLE` over Jolokia** — absence of truncation in the messages seen
  so far is not proof. When the Phase 4 Core client is connected,
  `MESSAGE_BODY_FULL` reads `AVAILABLE` because the Core channel returns full
  bodies irrespective of the management limit.
- Per-message `bodyTruncated` on the browse response is the primary, always-present
  signal; the capability row is the connection-level roll-up of "we have seen
  truncation here".

`broker/CapabilityProbe` therefore does **not** call `getAddressSettingsAsJSON`
for this capability. `DlqService` still does, for `deadLetterAddress` /
`expiryAddress`.

### 11.4 Net changes to the plan from Slice 0

1. `browse` returns a **plain array**, not a double-encoded string — simpler
   `decodeBrowse`.
2. `browse(int,int,String)` **pages at the broker** — drop task **2.5** and the
   `artemis-studio.browse.max-rows` setting; the broker's `managementBrowsePageSize`
   is the ceiling. `MessageService.browse` passes `(page, size, filter)` straight
   through.
3. Truncation has an **explicit marker** (`, + N more`) — no length-heuristic
   ceiling comment needed.
4. **`MESSAGE_BODY_FULL` is observed, not probed** — ADR-0021's Decision and the
   `broker-capabilities` spec delta are corrected per §11.3 (three states:
   `UNKNOWN` default, `UNAVAILABLE` once a truncated value is seen, `AVAILABLE`
   only with the Phase 4 Core channel).
5. Single-message **detail == a scoped browse** — there is no richer call; the
   detail endpoint runs `browse(1, N, filter)` and picks the row by `messageID`.
6. Invalid filter → map to **400**, not 502.

## 12. Phase 4 surface checks — Core client, push events, faithful I/O

What actually shipped, against the plan in §8-§9:

- **Subscription, not polling.** `CoreEventClient` opens one Core connection per
  *live* node, `session.createConsumer(session.createTopic("activemq.notifications"))`,
  and drains it with `consumer.receive(250)` on a virtual thread — never a
  `MessageListener`. §8's warning was correct: a listener deadlocks against
  `close()` on the pinned `artemis-jakarta-client:2.56.0` / broker 2.44 pairing.
  The poll loop is the workaround, not a style choice.
- **Failover is followed.** `CoreSubscriptionManager.reconcile(clusterId, endpoints)`
  runs at the end of every tier-A tick, off the same liveness list the HA alert
  uses. Stopping the primary container moves the subscription to the survivor
  within one tier-A cycle (≤5s) — no separate failover detector.
  `verdictFor(clusterId)` stays `Connected` throughout, backed by whichever node
  is currently subscribed.
  `NOTIFICATIONS` reads this cached verdict; `CapabilityProbe` never opens a
  Core connection on a request path (that would cost a TCP handshake per
  `GET /clusters/{id}` call).
- **Topology-resolution trap confirmed.** §8's prediction held: the broker
  advertises `<connector>` hosts (`artemis-primary:61616`) that resolve inside
  the compose network but not from wherever Studio's process runs against a real
  deployment. `useTopologyForLoadBalancing=false` plus
  `initialConnectAttempts(1)` / `reconnectAttempts(0)` stop the client from
  chasing that advertised topology; a manual Core URL override
  (`broker_node.core_url`, `PATCH .../nodes/{id}`) is the escape hatch when the
  advertised host is genuinely unreachable.
- **Faithful bodies, no truncation.** `CoreMessageTransport.browse()` uses a
  `QueueBrowser` — real typed properties, real `byte[]` bodies, no
  `management-message-attribute-size-limit` in the path at all. §11's
  `MESSAGE_BODY_FULL`-as-observed-capability idea (§11.3/§11.4) did **not**
  ship; ADR-0021/ADR-0029 instead disclose truncation per message
  (`bodyTruncated`) and add a `transport: "CORE" | "JOLOKIA"` field to every
  message response — simpler than a fifth capability, and it says exactly which
  channel served *this* call rather than a connection-wide roll-up.
- **Deep-page fallback.** A `QueueBrowser` has no server-side offset — page *N*
  costs a local walk of `N × pageSize`. Past `BROKER_PAGE_CAP` (200) messages
  deep, `CoreMessageTransport` falls back to the Jolokia transport for that call
  and the response says so (`transport: "JOLOKIA"`). Silently taking the slow
  path would be as dishonest as silently truncating a body.
- **Mutations stay Jolokia-only.** move/retry/delete/expire/purge are addressed
  by id or selector and carry no payload — no fidelity dimension, so
  `CoreMessageTransport` delegates them straight to the Jolokia transport
  (ADR-0029, D9). Only browse and send got a second implementation.
- **Event history and push.** Every notification is normalised to a
  `BrokerEvent` and buffered into `broker_event` (`BrokerEventWriter`, bounded
  queue, JDBC batch insert, visible `dropped` counter on overflow — no silent
  loss). The SSE `events` topic carries the row itself plus an `id:` line
  (`broker_event.seq`), so a reconnecting client replays what it missed via
  `Last-Event-ID` (capped at 500) instead of refetching a whole page.
  Notification-derived `consumers`/`sessions`/`connections` staleness signals
  are coalesced to at most one publish per topic per second per cluster
  (`TopicCoalescer`) — those views are live per-node Jolokia fan-out reads, and
  an uncoalesced chatty broker would turn push into a self-inflicted load
  problem.

## 13. Phase 5 slice 0 spike — request-reply correlation

`RequestReplySpikeIT` (real broker via `ArtemisIntegrationTest`) ran both reply
patterns and a stuck-request case, answering the seven questions design.md D1
posed before slice 1 committed to a join strategy.

### Verdict table

| # | Question | Verdict |
|---|---|---|
| 1 | Does `BINDING_ADDED` fire for a JMS `TemporaryQueue`? | Not reliably observed in this run — see note below. A separate, unrelated `BINDING_ADDED` fires for the *notification subscriber's own* non-durable queue (see note). |
| 2 | Is temporariness distinguishable from `_AMQ_Binding_Type` alone? | **No.** Every binding observed — the durable notification-subscriber queue and (by elimination) the temp reply queue — carries `_AMQ_Binding_Type=0` (`LOCAL_QUEUE`). The enum does not encode temporary vs. durable. **Moot for the design**: Studio already knows a destination is the temp reply queue because it read `JMSReplyTo` off the browsed request message — it never needs the binding notification to say so. |
| 3 | Does `BINDING_REMOVED` fire on `TemporaryQueue.delete()`? | **Yes**, confirmed: `BINDING_REMOVED` then `ADDRESS_REMOVED` fired for the temp queue's routing name immediately after `TemporaryQueue.delete()`. |
| 4 | Does `MESSAGE_EXPIRED` fire, with a matching `_AMQ_Message_ID`? | **Not observed** — not because the plugin is off, but because a message sent to an address with **no bound queue and no consumer ever created** appears to be dropped at send time; no `ADDRESS_ADDED`/`BINDING_ADDED` notification fired for the stuck-request queue at all, so nothing was ever queued to expire. This is an environment/config fact (auto-create-queues), not a client-side gap — `TIMED_OUT` for this case still resolves correctly via the deadline sweep (design.md D4), which does not depend on `MESSAGE_EXPIRED` firing. |
| 5 | What does a `QueueBrowser`/consumer-received Core message expose for `JMSReplyTo`/`JMSCorrelationID`/`JMSMessageID`/`JMSExpiration`? | All four are populated exactly as expected on a message received via a plain consumer or a `QueueBrowser`: `getJMSMessageID()` returns a real `ID:...` value (unlike notifications, where it is null — confirmed still distinct), `getJMSCorrelationID()` and `getJMSReplyTo()` round-trip whatever the sender set. |
| 6 | Does `getJMSReplyTo().toString()` match `_AMQ_RoutingName` from `BINDING_ADDED`/`CONSUMER_CREATED`? | **Yes, confirmed.** Requester-side: `ActiveMQTemporaryQueue[2324a578-fa95-471b-bd20-03bfd0da37d1]`. The `CONSUMER_CREATED`/`MESSAGE_DELIVERED`/`BINDING_REMOVED` notifications for that queue all carried `_AMQ_RoutingName=2324a578-fa95-471b-bd20-03bfd0da37d1` — the UUID inside the `toString()` wrapper, verbatim. The join key is: extract the substring between `[` and `]` (or match `Destination.toString()` by simple `contains`) and compare to `_AMQ_RoutingName`. |
| 7 | Does a responder's `CONSUMER_CREATED` `_AMQ_SessionName` reappear on a later event tying a delivery to that session? | Not directly exercised as a distinct scenario; `MESSAGE_DELIVERED` in this broker version does **not** carry `_AMQ_SessionName` (only `_AMQ_ConsumerName`, `_AMQ_Message_ID`, `_AMQ_RoutingName`, `_AMQ_Routing_Type` — matching §7's catalogue exactly). Tying a delivery to a specific consumer session is not possible from notifications alone; not needed by the design, which only tracks *whether a responder is present*, not which session served a given reply. |

### An unrelated finding worth recording

Every test run showed a `BINDING_ADDED` + `CONSUMER_CREATED` pair for a
short-lived UUID-named queue with no relation to any address in the test. This
is **the spike's own `activemq.notifications` subscriber** — subscribing to a
topic address auto-creates a non-durable queue bound to it (exactly what the
`createNonDurableQueue` permission from §7 is for). `RrNotificationObserver`
must filter these out: any binding/consumer event whose address is
`activemq.notifications` itself is Studio's own plumbing, not a traced
request-reply address, and must never be mistaken for a responder or a reply
destination.

### Net changes to the plan

None to the join strategy, schema, or state machine — every design.md D2/D3
assumption held or turned out not to matter (Q2, Q7). One clarification carried
into `RrNotificationObserver`'s implementation (task 6.2): filter out
notifications whose address is `activemq.notifications` itself, to avoid ever
treating the observer's own subscription queue as request-reply traffic.

---

## 14. v1.0 surface checks — config diff, slow consumers

Run against `just dev-up` (Artemis 2.44.0, replication pair, primary `:8161`
broker=`"primary"`, backup `:8261` broker=`"backup"`) before designing the
config-diff and slow-consumer slices. Four of the five questions were assumptions
those slices rested on; two of them could have changed the shape of the feature.

| # | Question | Verdict |
|---|---|---|
| 1 | Does a **passive backup** answer management reads, and how much surface does it expose? | **Full surface.** The backup's broker MBean returns the *same 90 attributes* as the primary — set difference in both directions is empty. It is `Started=true`, `Active=false`, `Backup=true`, `ReplicaSync=true`, and reports the same `NodeID`. No capability gating is needed: the pair diff works against a passive backup exactly as against a primary. |
| 2 | Full attribute list and operation catalogue, both sides? | **Identical.** 90 attributes and **78 operations** on each side; no operation exists on one and not the other. Of the 25 attributes that differ, 22 are runtime counters that read 0 on the passive side (`AddressCount`, `QueueCount`, `ConnectionCount`, `Total*`, `SessionCount`, `DiskStoreUsage`, `AddressNames`, `QueueNames`, `AuthenticationSuccessCount`, `Uptime*`, `CurrentTimeMillis`, `Status`) and 3 are HA-role attributes (`Active`, `Backup`, `HAPolicy`) plus `Name`. |
| 3 | Is `slowConsumerThreshold` returned by `getAddressSettingsAsJSON`? | **No — the Phase-0 capture was right.** The operation returns exactly 18 fields and the only slow-consumer field among them is `slowConsumerThresholdMeasurementUnit` (`MESSAGES_PER_SECOND`). Neither `slowConsumerThreshold`, `slowConsumerCheckPeriod` nor `slowConsumerPolicy` is exposed. **Native slow-consumer detection state is therefore UNKNOWN, not "off"** — Studio cannot observe the difference, and must say so (non-negotiable #5) rather than guess. |
| 4 | Do `getRolesAsJSON` and the acceptor MBeans exist and answer? | **Yes, both sides, identical output.** `getRolesAsJSON("#")` returns the `amq` role's twelve permission flags. Acceptors are better read as the **`AcceptorsAsJSON` attribute** than through the `component=acceptors,*` MBean search: it carries `name`, `factoryClassName`, `params` (port, host, protocols, buffer sizes) and `extraProps` in one entry that batches with everything else, whereas the search costs a second round trip and returns only the queried node's own acceptor. Both `securitySettings` and `acceptors` diff sections are therefore viable. |
| 5 | Does `broker_event`'s type CHECK accept `CONSUMER_SLOW`? | **There is no CHECK.** `010-broker-events.sql:14` declares `type TEXT NOT NULL` with no constraint — only an index on it. No changeset is needed for `CONSUMER_SLOW`. |

### Consequences carried into the slices

- **Slice 4 is not degraded.** Q1 and Q2 remove the "primary vs primary, pair-diff
  only when the backup answers" fallback the plan hedged for. The passive-backup
  detection stays in the design as a **guard** — a broker that does answer thinly
  must be told about, not half-diffed — but it is not this stack's behaviour.
- **Address settings resolve for any match on either side.**
  `getAddressSettingsAsJSON` returns *effective* settings for an arbitrary match
  string, including one the node hosts no address for: querying `PHASE3.DST` and
  `no.such.address.anywhere` against the backup (`AddressCount=0`,
  `AddressNames=[]`) both return the same resolved settings the primary returns.
  So the address-settings comparison is not blocked by the backup having no live
  addresses. There is **no operation that enumerates configured match patterns**,
  so the compared set is `#` ∪ `AddressNames(left)` ∪ `AddressNames(right)`,
  capped and disclosed.
- **Expected-difference class is confirmed by observation, not assumed.** `Name`
  (`primary`/`backup`) and `HAPolicy` (`Replication Primary w/quorum voting` /
  `Replication Backup w/quorum voting`) differ by design on a correctly
  configured pair. `NodeID` and `JournalDirectory` are **identical** here — so
  NodeID is only an expected difference when comparing two *different* logical
  nodes, not the two endpoints of a pair.
- **Slow-consumer capability is three-state with UNKNOWN as the answer here.** Q3
  settles it: `CapabilityProbe` reports UNKNOWN and ships the enabling
  `broker.xml` snippet.
- **`paused` is already on the wire.** Checked while resolving the paused-queue
  scoping question: a `listQueues` row carries `paused` (and `persistedPause`)
  alongside the counters Studio already parses. A paused queue with a backlog and
  consumers is *correctly* slow but operationally expected, so `queue_snapshot`
  gains a `paused` column (a new changeset — `005-broker-cache.sql` is released)
  and `SlowConsumerCondition` excludes paused queues from its universe. No extra
  broker call: the field is in the page Studio already reads.
