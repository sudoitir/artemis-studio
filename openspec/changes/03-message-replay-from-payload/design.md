## Context

See `proposal.md` — Why. Only the constraints that shape the approach are recorded
here.

What exists and is reused:

```
MessageService.send(...)            requireCluster(message:send) → audit.begin →
                                    MessageTransport → audit.succeed|fail
MessageTransport                    ADR-0029 — Core (faithful) | Jolokia (degraded)
MessageBrowser                      browse + detail, the body and headers a
                                    replay starts from
rr payload capture                  bounded capture, size-capped by setting
DLQ views                           cross-node dead-letter presentation
```

Constraints that follow:

- **A browsed message is not a complete message.** Jolokia browsing returns a
  broker-rendered view. A body that is not text may be truncated, encoded, or
  absent depending on transport. Replay fidelity is therefore transport-dependent
  and cannot be promised uniformly.
- **Request-reply payload capture is size-capped by an operational setting.** A
  captured payload can be a prefix of the original. Replaying a prefix as if it
  were the message is a correctness bug, not a degradation.
- **`MessageService.send` already audits and permission-checks.** Replay should be
  a caller of it, not a parallel path — otherwise there are two send paths with two
  audit shapes.
- **The bulk cap and dry-run live in `MessageService`.** A bulk replay reuses them.

## Goals / Non-Goals

**Goals**

- Make a stored payload actionable without ever misrepresenting it as the original.
- Make replayed traffic identifiable downstream, permanently.
- Refuse rather than degrade when fidelity cannot be guaranteed.

**Non-Goals**

- No preservation of ordering, timestamps, or message id. Impossible; stated.
- No automatic or scheduled replay. See D5.
- No replay of a whole queue's history. A selection, capped, is the scope.
- No new storage. Replay reads payloads the product already captures.

## Decisions

### D1 — Replay is a send, not a restore

Rejected: model replay as reinstating the original message.

It cannot be done. The broker assigns message ids; a "restored" message with the
original id either fails or produces two messages claiming one identity. Timestamps
would be wrong or forged. Ordering relative to the original is gone.

So replay composes a new message from stored content and says so — in the API, in
the provenance header, and in the UI wording. Calling it "restore" would be a
better-sounding word for a worse-understood operation.

### D2 — Broker-owned headers are dropped; application headers are carried

Carried: the application-set properties, the content type, and the correlation
identifier that request-reply tracing depends on.

Dropped: message id, delivery count, expiry, timestamp, and the dead-letter
bookkeeping (`_AMQ_ORIG_ADDRESS` and its relatives) that records where a message
was originally routed from. Carrying dead-letter bookkeeping onto a fresh message
would make the DLQ's own view of provenance wrong.

The exact broker-owned header set is confirmed against the Artemis API at apply
time, not asserted here.

### D3 — Provenance is a header, not only an audit row

The audit row records that a replay happened. The header is what lets a consumer,
a log, or a later investigation tell that *this* message is a replay — after it has
left Studio entirely.

It carries: the original message id, the audit event id, and a replay depth.
Nothing that would let a downstream system be tricked into trusting it — it is a
label, not a credential.

### D4 — Replay depth, enforced

A replay carries depth n+1 from a message whose depth is n. Above a configured
ceiling, the replay is refused.

The failure this prevents is concrete: a message is replayed, fails again,
dead-letters again, and an operator working a queue of DLQ entries replays it a
second and third time without noticing it is the same message coming back. The
depth makes the loop visible and then stops it.

### D5 — No automatic replay, ever

Rejected: replay on dead-letter, replay on a rule, scheduled replay.

An automatic replay is an unbounded message generator with a failure mode that
looks like a working system. Every replay is an operator action with a
confirmation, or it is not a feature this product should have.

### D6 — Fidelity is gated, not degraded

If the body cannot be reproduced faithfully — a binary body over a Jolokia-only
connection, or a request-reply payload that was truncated by the capture cap — the
replay is refused, with the reason and, where it applies, the `broker.xml` snippet
that would enable the Core transport.

The alternative, sending a best-effort approximation, produces a message that is
subtly wrong on a path where nobody is looking for subtle wrongness. Non-negotiable
#5 applied to fidelity rather than availability.

### D7 — Bulk replay reuses the existing cap machinery

Selecting DLQ entries and replaying them is the real operational shape. It goes
through the same dry-run estimate, the same ADR-0022 cap, the same `override`, and
the same typed confirmation as a bulk delete. A partial failure reports which
messages replayed and which did not, by original id.

## Risks

- **Duplicate delivery.** A replay of a message the consumer did in fact process
  produces a duplicate. This is inherent to replay and is the operator's judgement;
  the confirmation states it and the provenance header lets a consumer with an
  idempotency key discard it.
- **A truncated request-reply payload looks complete.** Mitigated by D6 — capture
  records whether it was truncated, and a truncated payload is not replayable. This
  requires the capture to have stored that flag; if it does not, `tasks.md` adds it.
- **Provenance headers leak into business logic.** Named with a clear product
  prefix and documented as reserved.

## Open for refinement

D4's ceiling value, D2's carried/dropped split, and whether D6 should refuse or
warn-and-allow for a truncated payload are all open. Revise this file with
`tasks.md` and the spec deltas when they settle.
