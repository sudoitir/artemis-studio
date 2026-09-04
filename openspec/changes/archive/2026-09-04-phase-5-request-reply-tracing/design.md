## Context

See `proposal.md` - Why for motivation and the hard constraint (no correlation
identity in `activemq.notifications`). Starting state: `007-request-reply.sql`'s
`rr_expectation`/`rr_flow`/`rr_event` exist unused; `rr_event` has no primary key
so no JPA entity can map it. `CoreEventClient` (Phase 4) dispatches to exactly one
`BrokerEventSink` (`BrokerEventWriter`). `CoreMessageTransport.open()` builds a
factory, connection, and session per call and tears all three down after. Neither
was a problem at operator-click frequency; both are wrong for a sampler running
every few seconds.

## Goals / Non-Goals

**Goals:**
- Reconstruct both reply patterns (temporary reply queue, shared reply queue)
  from a combination of push notifications and sampled Core browsing.
- Make the sampling ceiling a stated product property (coverage ratio shown with
  every latency figure), not a hidden bug.
- Reuse Phase 4's Core client and notification pipeline; extend, not duplicate.
- Ship broker-friendly: bounded browse depth, page 1 only, no deep queue walks.

**Non-Goals:**
- Perfect coverage via broker-side instrumentation (a server plugin) — rejected,
  it would put Studio code inside the broker.
- Persisted latency history / charts over time — Phase 6 (`metric_sample`).
- Alerting on stuck flows — Phase 7 evaluates rules; this phase only exposes counts.
- Message replay from captured payloads — v1.0.
- Distributed tracing / trace-context propagation — requires cooperating
  applications; Studio observes uninstrumented ones by design.

## Decisions

### D1 — Spike before design commitments harden

`RequestReplySpikeIT` (extends the existing `ArtemisIntegrationTest` singleton
container) runs before slice 1 and answers seven questions that can each overturn
a decision below — most importantly whether a JMS temporary queue's binding is
distinguishable from a durable one in `BINDING_ADDED`, and whether
`getJMSReplyTo().toString()` matches the `_AMQ_RoutingName` the notification
carries (the temp-queue join key). Findings go in
`docs/broker-management-notes.md` §13. Alternative considered: design directly
against the `CoreNotificationType` enum and fix in slice 3 when wrong — rejected,
the correlator's state machine is the part most likely to be wrong and it is the
flagship; a wrong foundation costs more slices to unwind than one spike costs to run.

### D2 — Notification-anchored, browse-sampled correlation

Notifications supply lifecycle facts (temp-queue bind/unbind, consumer up/down,
expiry) via a new `RrNotificationObserver` consuming the same `BrokerEvent`
stream `BrokerEventWriter` already does. A new `RrSampler` polls page 1 of each
traced address over a pooled Core connection for correlation identity
(`JMSCorrelationID`, `JMSReplyTo`, `JMSMessageID`, `JMSExpiration`). Both produce
one normalised `Observation` type so the correlator does not know which channel a
fact came from. Alternatives considered: notifications-only (rejected — no
correlation join, so no shared-reply-queue support and unreliable temp-queue
latency for apps that cache a reply queue per session); browse-only (rejected —
loses `RESPONDER_DROPPED`/`ORPHANED` detection, which needs consumer-lifecycle
notifications, not polling).

### D3 — Reply join tries both JMS conventions plus the temp-queue destination

A responder conventionally echoes the request's `JMSMessageID` into the reply's
`JMSCorrelationID`, but many applications echo the request's own
`JMSCorrelationID` instead. The join query (`ix_rr_flow_open_reply`) matches on
either, plus the temp-queue destination, oldest-in-flight-first — so a reused
correlation id on a shared reply queue resolves to the longest-waiting request.

### D4 — Deadline sweep is a scheduled SQL `UPDATE`, not an in-memory timer

`TIMED_OUT` and `ORPHANED` are not produced by any single observation — they are
"nothing happened for long enough." A `@Scheduled` sweep runs one indexed
`UPDATE ... WHERE state = 'AWAITING_REPLY' AND deadline_at < now() RETURNING ...`,
choosing `ORPHANED` vs `TIMED_OUT` from whether `responder_consumer` was ever set.
Survives restart for free since Postgres is the store of record; an in-memory
timer wheel would not.

### D5 — Reuse Micrometer, `pooled-jms`, and Caffeine; no new state-machine or scheduler library

| Need | Choice | Rationale |
|---|---|---|
| Latency percentiles | Micrometer `Timer` (`publishPercentiles`) | Already on the classpath via `spring-boot-starter-actuator` + `micrometer-registry-prometheus`; correct time-windowed percentile merging; free `/actuator/prometheus` export. |
| Core connection reuse | `org.messaginghub:pooled-jms` (`JmsPoolConnectionFactory`) | Version-managed by the Boot 4.1.0 BOM (3.2.2); handles concurrent sessions and eviction correctly, which a hand-cached `Connection` would get wrong under sampler-driven concurrent access. |
| In-flight join cache | Caffeine (Boot-managed, 3.2.4) | `expireAfterWrite` + `maximumSize` keeps the hot flow-key lookup off the DB without an unbounded map; Postgres stays authoritative. |
| Six-state machine | Plain `switch` over an enum | Eight transitions; a state-machine library is ceremony at this size. |

### D6 — Extend Phase 4's single-sink notification dispatch to a list

`CoreEventClient` takes `List<BrokerEventSink>` and dispatches to each, catching
and logging per-sink failures so `RrNotificationObserver` (best-effort) can never
break `BrokerEventWriter` (the history of record), or vice versa.

### D7 — Payload capture lives on `rr_event.detail`, no new column

`rr_event.detail` is already `JSONB` and already cascades on flow deletion. A
size-capped body preview plus properties on the `REQUEST_SEEN`/`REPLY_SEEN` rows
avoids widening the high-churn `rr_flow` table with a new changeset column that
would need its own storage-parameter consideration.

### D8 — Schema additions via a new changeset, never editing `007`

`007-request-reply.sql` is released (non-negotiable #7: never edit a released
changeset). `011-request-reply-keys.sql` adds: a primary key to `rr_event`
(`seq BIGINT GENERATED ALWAYS AS IDENTITY`, the same append-only-log exception
ADR-0028 already used for `broker_event`); `reply_address`,
`correlation_property`, `capture_payload` on `rr_expectation`; and dedup
(`uq_rr_flow_request`), sweep (`ix_rr_flow_deadline`), and join
(`ix_rr_flow_open_reply`) columns/indexes on `rr_flow`. `ALTER TABLE ADD COLUMN`
appends physically regardless of the alignment convention that governs `CREATE
TABLE` — noted in the changeset comment so a later reader does not read it as a
lapse.

## Risks / Trade-offs

- **[Sampling misses fast flows]** → Stated explicitly as a coverage ratio next
  to every latency figure and in the capability's Purpose; page-1-only sampling
  is deliberate (near-empty is healthy, a backlog on page 1 is exactly the stuck
  set) rather than an attempt at completeness.
- **[Temp-queue join key might not be the routing name]** → D1's spike answers
  this before slice 3 commits to the join query; the design explicitly names the
  fallback question (D1, item 6) rather than assuming success.
- **[Sampler adds Core load alongside Phase 4's subscriptions and Phase 2's
  Jolokia tiers]** → Bounded page depth, per-cluster sample budget, and a pooled
  connection (D5) instead of connect-per-tick; the sampler is its own trigger
  task, not a fourth Jolokia scrape tier, so it never contends with
  `NodeCallLimiter`.
- **[Two consumers of one notification stream: a slow one could add latency]** →
  D6 dispatches synchronously but catches per-sink; if a sink proves slow in
  practice the mitigation is making that sink itself async, not blocking the
  other — deferred until observed, not designed against speculatively.

## Migration Plan

Additive throughout: one new changeset, one new capability, three modified
capabilities that only add fields/behavior (a widened `BrowsedMessage`, a pooled
connection strategy, a list instead of a single sink). No existing behavior is
removed. Rollout is the standard `just db-status` / Boot-managed Liquibase apply;
rollback is the changeset's own `--rollback` blocks. No feature flag — the whole
capability is inert until an operator creates an expectation, per non-negotiable
#5 (it also explains itself as unavailable when Core/notifications aren't
reachable).

## Open Questions

None — the seven broker-behaviour unknowns that would otherwise be open
questions are resolved by the slice-0 spike before slice 1's design commitments
(join keys, temp-queue detection) are acted on, per D1.
