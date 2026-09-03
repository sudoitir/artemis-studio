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
