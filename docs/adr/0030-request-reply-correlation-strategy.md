# ADR-0030: Request-reply correlation is notification-anchored and browse-sampled, with a disclosed coverage ceiling

- **Status**: accepted
- **Date**: 2026-09-04
- **Deciders**: Mahdi Amirabdollahi

## Context

Phase 5 reconstructs request-reply flows from `007-request-reply.sql`'s
unused `rr_expectation` / `rr_flow` / `rr_event` schema. The obvious approach —
drive everything from `activemq.notifications`, which Phase 4 already
subscribes to — does not work: confirmed against `docs/broker-management-notes.md`
§7/§13, `MESSAGE_DELIVERED` carries an address, a consumer, and a message id,
and nothing else about the message. There is no `JMSCorrelationID`, no
`JMSReplyTo`, anywhere in the notification catalogue. The correlation-id join
`rr_flow` demands cannot come from the push stream.

The alternative — browsing messages with the Core client
(`CoreMessageTransport`, ADR-0029) — does carry that identity, but only for
whatever is on the queue when Studio happens to look. A request-reply pair that
completes between two polls is never observed.

## Decision

**Split the signal by what each channel can actually prove.** Notifications
supply lifecycle facts: a temp reply queue's binding lifetime
(`BINDING_REMOVED`), whether a responder is currently attached
(`CONSUMER_CREATED`/`CONSUMER_CLOSED`), and — critically — `MESSAGE_DELIVERED`
on a known open temp-queue destination, which closes the loop for the
temporary-reply-queue pattern without ever needing to browse a per-request
queue nobody could have named in advance. A new `RrSampler` polls page 1 of
each declared request (and, for the shared-reply-queue pattern, reply) address
over a pooled Core connection (ADR-0031) for the identity notifications can't
carry: `JMSCorrelationID`, `JMSReplyTo`, `JMSMessageID`, `JMSExpiration`. Both
channels normalise into one `Observation` type; `RrCorrelator` does not know or
care which channel a fact came from.

**The result is deliberately sampling-based, and this is a stated product
property, not a bug to hide.** A request-reply that completes faster than the
sample interval is never observed. This is acceptable, and arguably desirable:
the flows worth surfacing are the ones that linger, and page-1-only sampling
means a near-empty queue (healthy) costs nothing extra while a backlog on page
1 (the interesting case) is exactly what gets seen. Every latency figure the
API reports carries a coverage-ratio estimate alongside it
(`GET .../rr/stats`); the system never reports sampled percentiles without
disclosing how much of the actual traffic they were sampled from
(non-negotiable #5).

**The reply join tries both JMS correlation conventions plus the temp-queue
destination**, oldest-in-flight-first: a responder conventionally echoes the
request's `JMSMessageID` into the reply's `JMSCorrelationID`, but many
applications instead echo the request's own `JMSCorrelationID`. Matching only
one convention would silently miss the other.

**A reply observed with no matching open flow is not discarded** — it becomes
a flow already in the `ORPHANED_REPLY` state, so "someone answered a question
we never saw" (usually because the request itself completed between samples)
stays visible rather than silently vanishing.

## Consequences

- Both reply patterns (temporary and shared reply queue) are supported without
  the sampler ever needing to poll a queue it could not have known about in
  advance.
- Reported latency is real for the flows it observed, always paired with a
  coverage estimate; there is no way to accidentally present it as complete.
- A request-reply pair faster than the sample interval never appears in the
  `AWAITING_REPLY` → `COMPLETED` history — an accepted gap, not an unknown one.
- `activemq.notifications` fires consumer/binding/delivery events for every
  address on the broker, not just traced ones; `RrNotificationObserver` filters
  every branch against the correlator's known traced addresses and open temp
  queues before forwarding, so ordinary broker-wide traffic cannot flood
  `rr_flow` or the in-memory responder tracker.

## Alternatives considered

- **Broker-side instrumentation (a server plugin) for complete coverage** —
  rejected. It would put Studio code inside the broker process, directly
  against the broker-friendly-by-construction non-negotiable, and creates a
  deployment dependency (a plugin jar, broker restarts) this product has
  avoided everywhere else.
- **Notifications only, no sampling** — rejected: purely structural (temp-queue
  binding lifetime = flow lifetime) with no correlation join at all, so the
  shared-reply-queue pattern is unsupported and temp-queue latency is wrong
  whenever a client reuses one reply queue across requests.
- **Browsing only, no notifications** — rejected: loses `RESPONDER_DROPPED` and
  `ORPHANED` detection, which need consumer-lifecycle events, not polling.
