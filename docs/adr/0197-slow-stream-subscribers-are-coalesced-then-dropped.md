# ADR-0197: Slow stream subscribers are coalesced, then dropped

- **Status**: accepted (amends [ADR-0018](0018-sse-hub.md))
- **Date**: 2026-10-13
- **Deciders**: Mahdi Amirabdollahi

## Context

ADR-0018 gives every subscriber its own queue and writer thread, so a client that stops reading delays
nobody. The queue was bounded by frame count only, and when it filled it was emptied for one `resync`. That
left three holes:

- A thousand large `events` frames could hold far more memory than a thousand signals.
- A client that was always a little too slow overflowed, resynced and overflowed again for ever, refetching
  every time.
- The buffer that holds live frames during a `Last-Event-ID` replay was unbounded, and a write blocked on a
  dead socket kept its writer thread, and the subscriber, for as long as the operating system waited.

The writer thread also lived only until the stream completed. A stream that ended before the thread was
started, or whose write failed, left a virtual thread parked on its queue.

## Decision

Each subscriber's outbound queue holds at most 1,000 frames and 1 MiB (the size of a frame is its text plus
a fixed overhead). The same bound applies to the replay hold buffer.

- **Signals are coalesced.** A queued change signal (a frame without data) is replaced in place by a newer one
  for the same topic and the same resources (`about`). Signals about other resources are kept.
- **Overflow resyncs.** When a frame does not fit, the queue is emptied and one `resync` takes its place, as
  before. When the replay hold buffer overflows, the held frames are dropped and `resync` is queued when the
  replay is released.
- **Repeated overflow, or a blocked write, drops the subscriber.** Three overflows within 60 seconds, or a
  single write blocked for more than 30 seconds (timestamped by the writer, checked when the next frame is
  queued, which the heartbeat guarantees), empty the queue, queue `reconnect` and the end of the stream, and
  take the subscriber out of the hub. A writer that is blocked in a write is interrupted. The client
  reconnects at once and sends its last event id, so it receives the replay it missed, or a `resync` when the
  gap is larger than the replay cap.
- **Every dropped frame is counted** in `studio.sse.dropped`, tagged `reason` (`coalesced`, `overflow`,
  `replay`, `blocked`).
- **The writer thread always ends.** It completes the emitter in a `finally`, so it ends on the end marker,
  on a failed write, on an interrupt and on a timeout alike. `stopDrain` before the writer starts prevents it
  from starting.

The limits are constants of `Subscriber`, not settings: they bound Studio's own memory, and an operator has
no better value to give.

### `broker_event.seq` replay and late commits

A replay from `seq` skips a row that commits after a higher one was already sent. `seq` is an identity value,
assigned at insert and visible at commit, so two transactions inserting at once can commit in either order.
Only `BrokerEventWriter` inserts into `broker_event`, and on one replica its flush is serial, but replicas
flush concurrently, so the skip was possible.

The writer now takes a transaction-scoped advisory lock before it inserts. One flush at a time, across
replicas, makes commit order equal to `seq` order, and `NOTIFY` is delivered in commit order, so a client
never sees a higher id before a lower one is committed. The cursor stays the plain `seq`. The cost is that
flushes queue behind each other, which they do not mind: a flush is one batch every second.

## Consequences

- A slow client's memory is bounded by 1 MiB, however long it stalls, and many stalled clients cost at most
  that each.
- A client that cannot keep up reconnects instead of resyncing for ever, and gets a replay. Reconnecting
  costs it one replay query.
- A signal that waits in the queue may be delivered earlier than a newer signal for another resource, since
  coalescing keeps the position of the first. Signals carry no data, so the order does not matter.
- Flushes of `broker_event` are serialised across replicas. A flush that never commits holds the others up
  until its transaction ends.

## Alternatives considered

- **A `(txid, seq)` cursor, replaying only below the oldest running transaction.** Correct, but the live
  frame is sent at commit with its `seq`, so the client would already hold a higher id; the replay would
  need a composite id on the wire and a new column. The lock gives the same guarantee with no schema change.
- **Dropping the oldest frames instead of emptying the queue.** Leaves the client with a gap it cannot see.
  A resync is explicit.
- **Settings for the bounds.** More surface to document and mis-set, for numbers that protect Studio itself.
