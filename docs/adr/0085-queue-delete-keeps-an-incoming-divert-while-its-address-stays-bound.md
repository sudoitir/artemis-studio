# ADR-0085: Queue delete keeps an incoming divert while its address stays bound

- **Status**: accepted
- **Date**: 2026-09-19
- **Deciders**: Artemis Studio maintainers
- **Amends**: [ADR-0084](0084-queue-delete-removes-dependent-diverts-and-disconnects-consumers-on-request.md)
  D1, the case where a divert whose source is the queue's address keeps that address bound.

## Context

ADR-0084 D1 removes a divert that forwards into the queue's address when the queue is the
last *queue* bound to that address. The harm it prevents comes from an address left with
nothing bound: the next forwarded message re-creates the queue, or, with auto-create off,
every send to the divert's source fails.

ADR-0084 D4 keeps a divert whose source is the queue's address, because it is a binding
that keeps the address alive and routing after its last queue is gone (§17 Q2). When both
kinds of divert exist, `SRC → DST → OTHER`, deleting DST's last queue leaves DST bound
through the outgoing divert. D1 as written still removes the incoming divert, which cuts
the chain: producers to SRC stop reaching OTHER. The queue delete did not otherwise touch
that path. That is the "changes where live traffic goes" harm D4 gives as its reason to
keep diverts.

## Decision

**An incoming divert is a dependent only when the delete leaves the address with no
queue and no divert bound to it.**

1. A divert that forwards into the queue's address is removed with the queue only when
   the queue is the last queue on the address *and* no divert has that address as its
   source. Capture taps do not count: their subscription removes them once the queue is
   gone (ADR-0084 D5).
2. Otherwise, when the queue is the last queue on the address, the incoming diverts are
   kept and the preview names them as kept, with the diverts from the address that keep
   it bound.
3. The rest of ADR-0084 stands.

## Consequences

Good: deleting a queue in the middle of a divert chain no longer silently cuts the chain.
The preview names the incoming divert as kept, and why.

Bad: after the delete, the incoming divert keeps forwarding into an address with no
queue. Messages route on through the outgoing divert and are not held on that address.
That is what the chain did for every message the queue did not hold, so it is not new
behaviour.

## Alternatives considered

- **Keep ADR-0084 D1 as written.** It removes a divert whose forward target still routes,
  which breaks a path the operator never asked to change.
- **Remove the outgoing divert too, so the address is really orphaned.** Rejected by
  ADR-0084 D4 for the same reason: it changes where live traffic goes.
