## Why

Studio moves messages only between queues on the same broker. Operators must also move
them to another broker: a queue piled up on a node with no consumers, a DR cluster
taking over a backlog, or a handful of stuck messages that need to sit where a consumer
is actually attached. Today that means leaving Studio for a hand-rolled consumer/producer
script. Such a script has no dry-run, no audit, no capacity check, and a real chance of
losing or duplicating messages when it dies halfway. This is roadmap item A:
cross-broker message transfer, forced redistribution of specific messages, and
queue-to-queue transfer across remote nodes.

## What Changes

- An operator can **transfer** messages from a queue on one node to a queue on another
  node, in the same cluster or in another registered cluster. The messages can be chosen
  by id, by filter, or as the whole queue.
  - **Move** removes the messages from the source.
  - **Copy** leaves the source untouched.
- **Forced redistribution** is a preset of the same operation: the same queue, on
  another live node of the same cluster. It lands the chosen messages on that node's
  own queue.
- **Queues of any size.** A transfer works in bounded chunks and never holds the
  selection in one broker call or in Studio's memory. The selection is frozen at run
  start (messages timestamped up to then), so a producer that keeps sending cannot make
  a run endless.
- **No message is lost or doubled by an interruption.**
  - A move first parks the selection in a Studio-owned staging queue on the source
    broker. It then relays each batch with a duplicate-detection id, committing on the
    target before acknowledging on the source.
  - A copy records what it has copied.
  - A stopped, failed, or interrupted run can be **resumed**. A move can instead be
    **returned to source**.
  - A run that still holds messages can never be discarded. A staging queue with no
    run is shown as orphaned, with a way to return its messages.
- **The target is checked before anything moves, and before every batch.** A transfer
  is refused when the broker would silently drop the messages:
  - an address-full or page-full policy of `DROP`;
  - a filtered target queue;
  - a ring queue smaller than the selection.

  It is also refused when the target cannot hold the selection, or when the target node
  is not live. Softer risks are warnings the operator must acknowledge: a last-value
  queue, duplicate detection being off, or the broker likely to redistribute the
  messages again. A check that could not be made is stated as unknown, never as a pass.
  When the target fills up mid-run, the run waits for capacity and then continues.
  If capacity does not come back within a bounded wait, the run stops, and it can be
  resumed.
- **Faithful messages.** The body, every Core body type, large messages, priority,
  expiration, and application properties arrive unchanged. The group id, correlation id
  and reply-to arrive unchanged too. Broker bookkeeping headers are dropped. Provenance
  headers record where each message came from and which run moved it.
- The usual Studio safety applies:
  - a preview with no broker side effect, and the bulk cap with a typed override;
  - typed confirmation for a move;
  - an audit event on both clusters, and permission checks on both clusters, re-checked
    during the run;
  - the per-node rate limiter, and live progress over SSE.
- **Core TLS becomes per cluster.** Two clusters whose brokers present certificates
  from different CAs can now both be connected over Core, which a transfer between them
  needs. Until now every Core connection shared one JVM-wide TLS context.
- The bulk runner's background-run control moves into `kernel.jobs`, as ADR-0093
  anticipated for its second user. Bulk behaviour is unchanged.

## Capabilities

### New Capabilities
- `message-transfer`: previewing, running, observing, stopping, resuming and returning a
  transfer of messages between queues on different nodes or clusters, with target
  acceptance checks and loss- and duplicate-safe delivery.

### Modified Capabilities
- `core-transport`: Core TLS trust material is per cluster rather than one JVM-wide
  context. Core adds a transacted relay session for moving messages between two nodes.

## Impact

- **Backend**
  - New module `feature/transfer`, with tables `transfer_run` and `transfer_copied`.
  - Endpoints under `/api/v1/clusters/{clusterId}/transfers/…`, a new SSE topic
    `transfer`, and transfer settings (batch size, rate, concurrency, capacity wait).
  - `platform/broker` gains:
    - a Core-API relay;
    - chunked `moveMessages`, and staging queue and address-settings management;
    - a Studio `SSLContextFactory`, registered as an Artemis SPI.
  - `kernel/jobs` gains `BackgroundRuns`, and `feature/bulk` moves onto it.
- **Broker footprint**
  - A durable queue `studio.transfer.<runId>` exists on the source broker while a move
    is in progress, together with exact-match address settings.
  - Studio's broker user needs create, delete, send and consume rights on
    `studio.transfer.#`. The UI shows the `broker.xml` snippet.
- **Frontend**
  - New feature `transfer`, with a transfer dialog, a run page, and a list.
  - A new kernel slot `messages.selection`, rendered by the messages view.
  - `AddressPicker` is exported from `features/queues`.
- **ADRs**
  - ADR-0097: cross-broker transfer is a staged, deduplicated relay.
  - ADR-0098: per-cluster Core TLS through an `SSLContextFactory`.
- **Dependencies**: none new. The Core API ships in the existing `artemis-jakarta-client`.
