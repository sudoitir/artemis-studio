## Context

See proposal.md for why this change exists. The current state that shapes the approach:

- **Moves stay on one broker.** Every move is a Jolokia `moveMessage(s)` on one node
  (`platform/broker/MessageOperations`). Message ids are broker-local.
- **Core is JMS-only and non-transacted.** It is reached through `CoreConnectionFactory`
  (JMS over Core, `artemis-jakarta-client` 2.57.0) and pooled per cluster and Core URL in
  `CorePool`.
  - Sessions are never transacted.
  - `CoreMessageTransport` maps only Text/Bytes bodies and has no large-message
    streaming.
  - Core TLS installs one JVM-default `SSLContext` (a recorded `ponytail:` ceiling).
- **Bulk operations own the only persisted, stoppable run.** It lives in `feature/bulk`.
  ADR-0093 says it is to be extracted when a second feature needs one.
- **Nodes are addressed per cluster.** `ClusterDirectory`/`ClusterNode` carry
  `artemisNodeId`, which is shared by a live/backup pair. `ServingNodes` picks the live
  endpoint.
- **Artemis 2.57 already provides the building blocks.** Verified against the jar:
  - `QueueControl.moveMessages(int flushLimit, String filter, String otherQueue, boolean rejectDuplicates, int messageCount)`
  - `copyMessage(long, String)`, by id only; there is no filter copy
  - `ActiveMQServerControl.createQueue(String json)`
  - `addAddressSettings(String, String json)`
  - `removeAddressSettings`
  - `getAddressSettingsAsJSON`
  - `getIDCacheSize` and `isPersistIDCache`
  - `getDiskStoreUsage`
  - `AddressControl.getAddressSize`, `isPaging` and `getAddressLimitPercent`
  - the `SSLContextFactory` SPI plus the `sslContext` transport parameter

## Goals / Non-Goals

**Goals:**
- Move and copy across nodes and clusters with no loss and, while target duplicate detection is on, no duplication, including across a Studio crash.
- Queues of any size: bounded memory in Studio and a bounded parked set on the broker.
- Refuse targets that would silently drop messages; wait for capacity instead of failing.
- Resume or return any run that did not finish.

**Non-Goals:**
- Transforming messages in flight.
- Scheduled or recurring transfers.
- Protocol-native relay: AMQP-published messages arrive in Core form.
- MCP tools for transfer. Deferred until the UI flow is proven.
- Multi-instance Studio coordination beyond the database claim that bulk already uses.

## Decisions

### D1 · A move is staged on the source broker, then relayed (ADR-0097)

Staging uses a Studio-owned durable anycast queue `studio.transfer.<runId>` on the source
broker, created with `auto-delete=false`. Its exact-match address settings are
`max-delivery-attempts=-1` and `redistribution-delay=-1`, with no dead-letter address. As
a result a relay rollback never dead-letters a message, and a clustered source never
redistributes the parked messages.

The selection reaches staging broker-side, atomically per call:
- ids go through `moveMessage` in batches of 50;
- a filter or the whole queue goes through the chunked
  `moveMessages(flushLimit, filter', staging, false, chunk)`.

`filter'` is `(<filter>) AND AMQTimestamp <= <t0>`. The run refills staging only while its
depth is below 2 × `batchSize`.

The relay uses the **Core API** (`ServerLocator` from the existing factory):
- a source session with manual acks, receiving from staging;
- a target session with transacted sends.

Each batch goes through these steps:
1. receive up to N messages;
2. build each outbound message (D3) with `_AMQ_DUPL_ID = studio:<runId>:<srcId>`;
3. send each to the FQQN `address::queue`;
4. commit the target;
5. commit the source.

**A repeated id refuses the whole target transaction** (verified against the broker while
building the relay). When any send in a transaction carries a duplicate id the target has
already seen, the broker rejects the commit with `ActiveMQDuplicateIdException` and
delivers none of the batch, including messages it had not seen. So after that refusal the
runner rolls the target back and resends the same batch one message per transaction: a
refusal then means only that message already arrived, and the source acknowledges it.

**Why this over the alternatives.**
- **A direct consumer on the source queue.** It would compete with live consumers, could
  not select ids, and would leave messages in Studio's hands during a crash.
- **A broker core bridge.** It needs broker-to-broker routing and connectors that
  separate clusters rarely have.
- **JMS.** It loses Map/Stream/Object fidelity and large-message streaming.

The FQQN pins a message to the named queue binding on the target node. This keeps a
forced redistribution from being load-balanced away on arrival.

A same-node transfer (source node == target node) uses the chunked `moveMessages` straight
to the target. It needs no staging and no relay.

### D2 · A copy is a browse relay with a Postgres ledger

Artemis has no filter copy, and a copy must leave the source alone.

The copy runs as follows:
- A browse-only Core consumer reads the source with `filter'`.
- The copy uses the same outbound build and duplicate id as a move, and commits the
  target per batch.
- After each batch it inserts the batch's source ids into
  `transfer_copied(run_id, message_id)` with a single `unnest` insert
  (`ON CONFLICT DO NOTHING`).
- A resume re-browses and skips ledger ids. A crash between the target commit and the
  ledger insert is absorbed by the duplicate id.
- The ledger rows are deleted when the run ends.

**Alternative considered:** a cursor on the last message id. It was rejected because
browse order is not id order after redelivery or a move, and consumers remove messages
between browses.

### D3 · The outbound build is one pure function

`OutboundMessages.from(ClientMessage source, Provenance p)` builds the outbound message
in three parts.

**What it copies**
- The body buffer and type byte, verbatim.
- Durability, priority, expiration (absolute), timestamp and userID.
- All properties, except the dropped set below.

**What it drops**
- `_AMQ_ORIG_ADDRESS`, `_AMQ_ORIG_QUEUE`, `_AMQ_ORIG_MESSAGE_ID`,
  `_AMQ_ORIG_ROUTING_TYPE`
- `_AMQ_ROUTE_TO`, `_AMQ_ROUTE_TO_ACK`
- `_AMQ_ROUTING_TYPE`
- `_AMQ_DUPL_ID`, which is replaced
- `_AMQ_ACTUAL_EXPIRY`

**What it adds**
- `_studio_transfer_run`
- `_studio_orig_cluster`, `_studio_orig_node`, `_studio_orig_queue`,
  `_studio_orig_message_id`

**Large messages.** A large message (`isLargeMessage()`) is saved to a 0600 file under
`${java.io.tmpdir}/studio-transfer/<runId>/` with `saveToOutputStream`. The target body
is then set with `setBodyInputStream`, and the file is deleted after the target commit.
Before saving, Studio checks that its free disk is at least the message size. The
directory is swept on startup.

**Why a temp file.** A pipe between the source and target streams would tie two
sessions' flow control together inside one transaction.

### D4 · Target acceptance is a pure verdict over one batched read

`TargetAcceptance.evaluate(Facts, Selection)` returns a list of `Finding(kind:
REFUSE|WARN|UNKNOWN, code, words, snippet?)`. The facts come from one batched Jolokia
POST per node, charged to the limiter:
- the queue's filter, routing type, ring size and last-value flag;
- the address settings JSON;
- the address size, paging state and limit percent;
- disk store usage and address-memory percent;
- the ID cache size and whether it is persisted;
- the consumer count;
- the source's persistent size and count.

The projected bytes are `count × (persistentSize ÷ messageCount)`. When the count is 0
or unknown, the projection is UNKNOWN.

The refusal and warning rules are the ones in the spec. The same function runs:
- in the preview;
- before every batch, where a threshold breach becomes `WAITING_FOR_CAPACITY`.

While waiting, the run polls with backoff capped at 30 s. After `capacityWait` it stops.

A `FAIL` rejection during a send rolls the batch back and takes the same path.

### D5 · Run model

`transfer_run` columns follow the row-padding order:
- **timestamps:** t0, created, started, updated, finished, expires;
- **bigints:** estimate, estimate_bytes, staged, delivered, not_transferred, expired, bytes, audit_event_id, target_audit_event_id;
- **integers and text:** mode, state, selection jsonb, source/target queue and address, plan_hash, last_error, options jsonb;
- **uuids:** id, source/target cluster, source/target node id, operator account;
- **booleans:** last.

Source and target are stored as the cluster plus the **Artemis node id**, the logical
node. The serving endpoint is resolved per batch, so an HA failover continues on the new
live node, which has the replicated durable staging queue.

A partial unique index enforces one active run per `(source_cluster_id, source_queue)`,
where state is RUNNING or WAITING_FOR_CAPACITY. `transfer_copied` has the primary key
`(run_id, message_id)`, `fillfactor=100`, and aggressive autovacuum.

**State transitions**
- `PREVIEWED` goes to `RUNNING`.
- `RUNNING` and `WAITING_FOR_CAPACITY` move back and forth.
- A run ends in `SUCCEEDED`, `PARTIAL` or `RETURNED`.
- `STOPPED`, `INTERRUPTED` and `FAILED` are resumable.
- From a resumable state a run goes to `RUNNING` by resume, or to `RETURNED` by return
  (via `RETURNING`).

A run that still has messages in staging is never deleted. Previews expire after 10
minutes, and the existing preview-housekeeping cron deletes them.

### D6 · `kernel.jobs.BackgroundRuns`

`BackgroundRuns` provides:
- `start(UUID runId, Operator operator, Runnable work)`, which runs on a virtual thread
  under `OperatorHandoff.runAs` and deregisters in `finally`;
- `requestStop(runId)`;
- `stopRequested(runId)`;
- `isActive(runId)`.

`BulkRunner` delegates to it with no behaviour change.

Recovery stays per feature, because the semantics differ:
- bulk interrupts and never resumes;
- transfer interrupts and offers resume.

### D7 · Per-cluster Core TLS (ADR-0098)

`StudioSslContextFactory` implements Artemis `SSLContextFactory`. It is registered in
`META-INF/services` with a priority above the default. It reads the connection's
`sslContext` parameter as a Spring SSL bundle name and returns that bundle's
`SSLContext`, cached per name. For any other connection it falls back to the default
behaviour.

The factory is instantiated by `ServiceLoader`, so it reaches Spring's `SslBundles`
through a static holder set by a `@Component` at startup. This is the one static seam,
documented in ADR-0098.

`CoreConnectionFactory` now emits `sslEnabled=true;sslContext=<bundle>` and no longer
calls `SSLContext.setDefault`.

### D8 · Limits and settings

The settings are in `kernel.settings` under `transfer.*`:

| Setting | Default |
|---|---|
| `batch-size` | 200 |
| `messages-per-second` | 1000 |
| `max-concurrent-runs` | 4 |
| `capacity-threshold-percent` | 90 |
| `capacity-wait` | 10m |

Rate limiting and permits:
- Each batch acquires one limiter permit on each node.
- Each Jolokia call goes through `JolokiaBrokerClient`, which is already limited.
- Pacing is a token bucket per run.

## Risks / Trade-offs

- **Target duplicate detection is off, or the ID cache is not persisted.** An interruption
  between the commits can duplicate one batch. → The preview warns and needs typed
  acknowledgement. Batches are small.
- **Staging holds messages outside the source queue during a move.** → They are durable
  on the same broker. Return to source is always offered. Orphans are detected. The name
  says what the queue is.
- **Studio's broker user needs rights on `studio.transfer.#`.** → The first failure is
  shown with the security-setting snippet. It happens before any message moves.
- **`AMQTimestamp` is producer clock.** A message with a future-skewed timestamp is
  excluded from a filter or whole-queue run. → The preview states the rule. An id
  selection is unaffected.
- **AMQP- or MQTT-published messages arrive as Core conversions.** → Stated in the
  preview.
- **The FQQN pin on the target means an arrival-time redistribution will not happen.** A
  later redistribution may still move the messages if the node has no consumers. →
  Warned.
- **Management-added address settings persist in the broker journal.** → They are removed
  when the run finishes or is returned. Orphan handling removes them too.

## Migration Plan

- Two additive changesets:
  - `feature/transfer/0001`;
  - the ledger, which is in the same file.
- No change to existing tables.
- The bulk runner refactor is internal.
- The TLS change affects only clusters with a TLS reference. Their Core connections are
  re-established on first use after upgrade.
- Rollback means the Liquibase rollback of the transfer changeset. Staging queues left on
  brokers are visible and can be drained with existing message tools.
