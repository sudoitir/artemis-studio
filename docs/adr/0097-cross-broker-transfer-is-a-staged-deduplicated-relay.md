# ADR-0097: Cross-broker transfer is a staged, deduplicated relay through Studio

- **Status**: accepted
- **Date**: 2026-09-21
- **Deciders**: Artemis Studio maintainers

## Context

Operators need to move and copy messages between queues on different brokers: another
node of the same cluster, or a node of another cluster (Roadmap A). A move is
destructive. When Studio dies halfway, the run must not have lost or doubled any
message. The run must also work on queues of millions of messages, and must not hand
messages to a target that will silently drop them.

The broker offers atomic moves only within one broker (`moveMessage(s)`). No broker
feature moves a *chosen* set of messages to a broker it has no cluster connection or
bridge to. Studio can always reach both brokers, because it manages them.

## Decision

We will implement a move as three phases.

1. **Stage on the source.** The run moves its selection broker-side, in bounded chunks
   (`moveMessages(flushLimit, filter, queue, false, count)`), into a Studio-owned durable
   queue `studio.transfer.<runId>` on the source broker.
   - The queue's exact-match address settings disable dead-lettering and
     redistribution.
   - A filter or whole-queue selection is frozen at run start with
     `AMQTimestamp <= t0`.
2. **Relay over the Core API.** Studio receives a batch from staging with manual
   acknowledgement, sends it to the target queue's FQQN in a transacted session,
   commits the target, and then acknowledges the source.
   - Every message carries `_AMQ_DUPL_ID = studio:<runId>:<sourceId>`, so a batch
     repeated after a crash between the two commits is dropped by the target.
3. **Finish or recover.**
   - When staging is empty, the run removes the queue and its settings.
   - A stopped, failed or interrupted run keeps staging and can be resumed or
     returned to the source.

A copy browses the source and relays in the same way. It records the copied source ids
in a Postgres ledger, so a resumed copy skips them.

Artemis holds the messages and Postgres holds only the run's bookkeeping. A target that
would silently drop messages is refused before anything moves. That covers a `DROP`
policy, a filtered queue, and a ring queue that is too small. Capacity is re-checked
before every batch, and a full target makes the run wait rather than fail.

## Consequences

- **No message is ever only in Studio.** Every message is on a broker at every moment,
  and an interrupted run is resumable. With target duplicate detection enabled (the
  broker default), no message is doubled.
- **Size is bounded.** Studio's memory and the parked set on the broker are bounded,
  whatever the size of the source queue.
- **The broker carries a visible artefact during a move:** the staging queue. Studio's
  broker user needs rights on `studio.transfer.#`. Studio must also detect orphaned
  staging queues and offer to return their messages.
- **The relay speaks Core.** Messages published over AMQP, MQTT or STOMP arrive in
  their Core form.
- **Selection depends on producer clocks.** A filter or whole-queue selection uses
  producer timestamps, so a message with a future-skewed timestamp is excluded. An id
  selection is exact.

## Alternatives considered

- **Consume directly from the source queue.** This competes with live consumers,
  cannot select by id, and leaves in-flight messages only in Studio during a crash.
- **An ephemeral broker core bridge.** The copy would be faithful and done by the
  broker, but it needs a network route and a connector from the source broker to the
  target. Separate clusters rarely have one, and the bridge leaves configuration on the
  broker.
- **Relay over JMS.** Map, Stream and Object bodies and large-message streaming
  degrade.
- **Track every moved message in Postgres.** This is a row per message for a
  million-message move. The staging queue already is the durable "remaining" set.
