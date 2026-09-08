# ADR-0066: A capture tap must never block a producer, so its address auto-creates

- **Status**: accepted
- **Date**: 2026-09-08
- **Deciders**: Artemis Studio maintainers

## Context

[ADR-0062](0062-message-capture-is-a-divert-into-a-ring-bounded-queue.md) D1 makes a capture
tap out of three objects: a non-exclusive divert, a **non-durable** ring-bounded queue, and an
address setting whose job is to stop the broker ever blocking or paging on Studio's account.
Non-durable was chosen deliberately — the capture queue is a disposable buffer and there is no
reason to write a second copy of every message into the journal.

Failing a replicated pair over during the end-to-end proof produced this, from the
**producer**, not from Studio:

```
javax.jms.JMSException: AMQ229203: Address Does Not Exist:
  artemis-studio.capture.<instance>.CAPTURE.PROOF.<subscription>.q
```

The reason is a mismatch in what replicates. A divert is a binding: it lives in the bindings
journal, so a promoted backup comes up **with the capture divert already deployed** — a useful
property, and the opposite of what D4 assumed. The capture queue is non-durable, so it does
not. The promoted broker therefore held a divert whose forwarding address did not exist, and
Artemis rejects a send to an address that cannot be routed. Studio had made a healthy address
unusable, which is exactly the harm non-negotiable #1 exists to prevent.

Two things had to be true and were not: the reconciler skipped rebuilding the queue on a node
whose divert was already present, and the capture address settings carried
`auto-create-addresses=false`, which stopped the broker from healing it on its own.

## Decision

**D1 — The capture address auto-creates; nothing else does.** The address settings on
`artemis-studio.capture.#` set `auto-create-addresses=true` and `auto-delete-addresses=true`,
with `auto-create-queues=false` and `auto-delete-queues=false` unchanged. A divert whose
forwarding address is momentarily absent then routes its copy into an address with no queue,
where it is dropped, and the producer is untouched.

The failure mode this chooses is the right one. Capture losing messages is a reported,
estimated, recoverable degradation; a producer failing to send is an incident Studio caused.

**D2 — A tap is healthy when it is being drained, not when its divert exists.** The reconciler
re-runs the whole idempotent install on any node it is not currently draining, rather than
skipping a node whose divert is present. The divert is the one part of a tap that survives
independently, so its presence is the least informative signal available.

**D3 — Non-durable stands.** The alternative — a durable capture queue, so the whole tap
replicates as one — would put every captured message through the journal, which is the cost
D1 of ADR-0062 exists to avoid. With D1 and D2 above, the window a failover opens is bounded
by one reconcile pass and costs dropped copies, not blocked producers.

## Consequences

- A promoted backup usually inherits the capture divert and needs only its queue rebuilt,
  which the next pass does. The coverage window still records the gap: the backup's
  `captured_from` starts when its tap started, never when the subscription did.
- Between promotion and that pass, copies are dropped at the auto-created address. The loss is
  visible through the same `CaptureLoss` estimate as any other drop, and the node reports
  `DEGRADED` rather than healthy.
- An auto-created capture address is auto-deleted once nothing is bound to it, so nothing
  accumulates from this.
- ADR-0062 D4's phrasing — "a promoted backup carries no runtime divert" — is wrong for a
  replicated pair. The reconciler's behaviour is unchanged, because it asserts rather than
  assumes; the documentation is corrected to match the measurement.

## Alternatives considered

- **A durable capture queue.** Correct in one step and the whole tap fails over together, at
  the price of journalling every captured message on a firehose. Rejected on cost; revisit if
  the dropped-copy window ever proves worse than the write amplification.
- **Creating the address on every pass regardless.** Narrows the window but does not close it,
  because nothing runs while Studio is down — and a Studio that is down must not be able to
  break a broker's routing.
