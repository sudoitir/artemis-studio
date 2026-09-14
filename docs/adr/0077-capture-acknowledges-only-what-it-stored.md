# ADR-0077: Capture acknowledges only what it stored, and backs off inside its bound

- **Status**: accepted
- **Date**: 2026-09-14
- **Deciders**: maintainers

## Context

Full capture (ADR-0062) drains a bounded, non-durable capture queue per tap with a
`CLIENT_ACKNOWLEDGE` consumer. It acknowledges in batches. Its stated rule was "commit
first, acknowledge second", so a crash could cause redelivery but never loss.

The implementation did not keep that rule:

- The index sink's write failure was caught and logged in `CaptureBus`, and the consumer
  acknowledged the batch anyway. With Postgres unavailable, every captured message was
  removed from the capture queue and never stored.
- One buffer was shared by every drain, and batches were written outside its lock. One
  drain could find the buffer empty and acknowledge while another drain's thread was
  still writing its rows.
- A message that could not be read was skipped. The next batch acknowledgement silently
  covered it.
- Messages rejected by the ingest rate cap were acknowledged without being counted.

Capture observes production traffic. It cannot slow producers or grow a broker
(non-negotiable #1, and the message-capture spec). So "never lose a message" cannot mean
retaining without limit while Studio's database is down. It has to mean never losing one
silently, and never losing one inside the bound the operator chose.

## Decision

We will:

1. **Buffer per drain.** Each drain owns its batch and writes it itself. The capture bus
   keeps only the per-subscription rate gate and its best-effort listeners
   (request-reply correlation), whose failures stay isolated from the store.
2. **Acknowledge only after commit.** A drain acknowledges only after its batch has
   committed.
3. **On a failed write:**
   - the drain does not acknowledge;
   - it waits with exponential backoff and jitter (1s to 5m), releasing its lock so
     shutdown is never blocked;
   - it then calls `session.recover()`, so the broker redelivers the batch;
   - it records the store failure, so the node is reported degraded with that cause once
     state can be written again.

   While a drain waits, messages accumulate in the capture queue, which is bounded by
   count and by bytes and drops the oldest. Anything dropped is counted by the existing
   loss measurement.
4. **Unreadable messages.** An unreadable message is recovered and retried. After three
   consecutive failures on the same message it is counted as loss with its cause, and only
   then acknowledged, after the rows before it have been stored.
5. **Rate-cap drops.** Rate-cap rejections are counted, and named as the cause when loss
   is reported.
6. **Stopping a drain.** A stopping drain closes its consumer first, which waits for
   in-flight delivery. It then stores what it holds, and acknowledges only if that store
   succeeded.

Duplicates can only arise when a store committed but its acknowledgement did not reach
the broker. They are absorbed by the writer's existing redelivery guard. A long database
outage produces none, because nothing was stored.

## Consequences

- A captured message is either stored or counted, never silently discarded.
- A database outage shorter than the capture queue's bound loses nothing. A longer one
  loses the oldest messages, and says how many and why.
- Production traffic is unaffected in every case: a paused drain only lets its bounded
  queue fill.
- Redelivery after a failed write costs broker work, but only once per backoff interval,
  never in a tight loop.
- A drain blocked in backoff holds its session. Capture sessions are separated from
  operator sessions (ADR-0079 / core-transport spec), so this cannot starve a browse.
- The loss figure remains an estimate from sampled counters. The cause attached to it is
  exact.

## Alternatives considered

- **Durable capture queue that pages to disk.** It would retain everything, but a broker
  could then grow and page because of Studio. Rejected by non-negotiable #1.
- **Keep acknowledging and buffer rows in Studio memory during an outage.** Unbounded,
  and lost on restart. It moves the failure rather than removing it.
- **Local disk spool between the drain and Postgres.** A second store to size, secure,
  back up and recover. Rejected as disproportionate for a bounded copy.
- **Acknowledge per message.** Correct, but one round trip per captured message is the
  throughput problem ADR-0062 D7 exists to avoid.
