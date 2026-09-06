## Why

"Message replay from a captured payload" is on the v1.0 roadmap, and it is the verb
that two shipped features are missing.

DLQ management discovers dead-letter addresses, presents their queues across nodes,
and offers a safe replay — where "replay" today means the broker's own
`retryMessage`, which sends a message back to the address it came *from* and works
only while the message is still sitting in the DLQ. Request-reply tracing captures
payloads for flows that went wrong, and then can only show them.

Both stop at the same place. An operator can see exactly what failed and has no way
to make it happen again on purpose. The captured payload — the thing the product
went to the trouble of storing — is inert.

The gap is sharpest in the case that matters most: a message was dead-lettered,
diagnosed, the downstream bug was fixed, and the DLQ has since been purged or the
message expired. The payload is in Studio. There is no path from there back onto a
queue.

## What Changes

**Replay becomes a first-class operation on `message-operations`,** available from
three places: a browsed message, a DLQ entry, and a captured request-reply payload.

**A replay is a new send with provenance (ADR-0051).** Studio does not pretend to
restore a message. It sends a new one carrying the captured body and the
application headers, and:

- broker-owned headers are **not** carried — message id, delivery count, expiry,
  and the dead-letter bookkeeping that says where the original came from. Carrying
  them would either be rejected or, worse, silently accepted and produce a message
  that lies about its own history;
- a provenance header names the original message and the audit event that produced
  the replay, so a replayed message is identifiable as one, downstream and forever;
- the destination defaults to the original address but is explicit and editable —
  replaying to a test address is the common first move, and the safe one.

**Loop safety.** A replay of a replay is allowed and is counted. The provenance
header carries a replay depth; a replay above a configurable depth is refused. The
failure mode this prevents is a replay that dead-letters and gets replayed again by
an operator who cannot see it has been round twice.

**Transport honesty.** Faithful replay of a non-text body needs the Core transport
(ADR-0029). Over a Jolokia-only connection, replay of a text body works and replay
of a binary body is capability-gated with the reason and the `broker.xml` snippet —
never silently degraded into a mangled message.

**No new permission.** A replay is a send. It requires `message:send` on the target
cluster, plus `message:read` to have obtained the payload. Adding `message:replay`
would create a grant that means "send, but only this way", which is not a real
authority boundary.

**Bulk replay is in scope, capped.** Replaying a selection of DLQ entries is the
actual operational need — one message at a time does not clear an incident. It
takes a dry-run and the ADR-0022 cap like any other bulk mutation.

## Impact

- **Studio can now put messages onto a broker that no broker ever gave it.** Until
  now every message Studio sent was composed by an operator in the send form. A
  replay sends stored content, which makes the provenance header and the audit
  record the only way to tell replayed traffic from original traffic. They are not
  optional.
- **Replayed messages are new messages.** Ordering relative to the original is not
  preserved and cannot be; consumers that assume ordering will see the replay out of
  sequence. The spec states this rather than leaving it to be discovered.
- Specs: `message-operations`, `dlq-management` and `request-reply-tracing` gain
  requirements.
- ADRs: 0051 (a replay is a new send with provenance).
- Not in scope: replaying an entire queue's history, scheduled replay, and any
  automatic replay on a failure. Every one of them turns an operator action into a
  loop generator.

## Open for refinement

Proposed, not settled. The replay-depth ceiling, whether the destination should
default to the original address or force an explicit choice, and how much of a
captured request-reply payload is faithfully reconstructable at all, are open.
Brainstorm before applying and revise `tasks.md` and the spec deltas with the
answers.
