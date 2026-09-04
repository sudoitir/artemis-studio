## Why

Every Artemis console shows queue depth. None answer the question an operator on
a request-reply bus actually has: *this request went in — where is my reply?*
`007-request-reply.sql` shipped `rr_expectation`, `rr_flow`, `rr_event` in Phase 1,
unused, waiting for this phase — including the six-state contract
(`AWAITING_REPLY`, `COMPLETED`, `TIMED_OUT`, `ORPHANED`, `RESPONDER_DROPPED`,
`ORPHANED_REPLY`) in `rr_flow`'s CHECK constraint. Phase 4 gave Studio the Core
protocol client, faithful message browse, and a live notification stream — the
three prerequisites this phase needed. This is the flagship feature and the last
blocker before it is buildable.

`activemq.notifications` carries no message correlation identity — confirmed
against `docs/broker-management-notes.md` §7, `MESSAGE_DELIVERED` carries an
address, a consumer, a message id, and nothing else about the message. The
correlation-id join `rr_flow` demands can only come from reading messages, so
this phase is deliberately sampling-based: notifications anchor lifecycle
(temp-queue binding, responder up/down, expiry), a non-destructive Core
`QueueBrowser` supplies correlation identity, and reported latency states its own
sampling coverage rather than hiding the gap.

## What Changes

- **New `RequestReplySpikeIT`**, a real-broker integration test (extends the
  existing `ArtemisIntegrationTest` singleton container) that runs both
  reply patterns and a stuck-request case before any product code, to settle
  broker behaviour the design depends on: whether a JMS temporary queue's
  binding notification is distinguishable from a durable one, whether
  `BINDING_REMOVED`/`MESSAGE_EXPIRED` fire and what they carry, and what a
  `QueueBrowser` message exposes for `JMSReplyTo`/`JMSCorrelationID`. Findings
  land in `docs/broker-management-notes.md` §13 and can adjust the design below
  before slice 1 starts.
- **New schema**, changeset `011-request-reply-keys.sql`: a primary key on
  `rr_event` (it currently has none — a JPA entity cannot map it), a reply
  address / correlation-property-override / payload-capture flag on
  `rr_expectation`, and dedup/deadline/reply-join columns and indexes on
  `rr_flow`. `007` is untouched.
- **New request-reply expectations** — an operator declares which request
  addresses to trace (address, optional reply address for the shared-queue
  pattern, deadline, samples/min, payload capture), CRUD over
  `/clusters/{id}/rr/expectations`, audited like any config mutation.
- **New sampler** (`RrSampler`) — polls page 1 of each traced request (and reply,
  for the shared pattern) address over a **pooled** Core connection
  (`pooled-jms`'s `JmsPoolConnectionFactory`, replacing `CoreMessageTransport`'s
  connect-per-call), broker-friendly by construction: bounded depth, no deep
  queue walk.
- **New correlator** (`RrCorrelator` + `FlowStateMachine`) — a pure state machine
  driving `rr_flow` through its six states from normalised `Observation`s
  (`RequestSeen`/`ReplySeen` from the sampler, `ResponderUp`/`ResponderDown`/
  `TempQueueUnbound`/`MessageExpired` from notifications), plus a scheduled
  deadline sweep for `TIMED_OUT`/`ORPHANED` (states no single observation
  produces). Reply join tries both JMS correlation conventions
  (`JMSCorrelationID` echoing the request's own, or its `JMSMessageID`) plus the
  temp-queue destination.
- **`activemq.notifications` gains a second consumer.** `CoreEventClient`
  currently dispatches to one `BrokerEventSink`; it fans out to a list so
  `RrNotificationObserver` can consume the same stream `BrokerEventWriter`
  already does, with one sink's failure isolated from the other.
- **`BrowsedMessage` gains `replyTo`.** It has `correlationId` but not the field
  that distinguishes the temp-queue pattern from the shared-queue pattern and
  names the reply destination.
- **New stats** — Micrometer time-windowed `Timer`s (p50/p95/p99) per
  cluster+address, published only alongside an explicit sampling-coverage
  ratio (`GET /clusters/{id}/rr/stats`) — never bare percentiles.
- **New flows API and screen** — `GET /clusters/{id}/rr/flows{,/{id}}` (paged,
  filterable) and a routed `rr` view: a flows table, a stuck/orphaned panel, a
  latency panel with the sampling caveat shown next to the chart (not in a
  tooltip), and a flow detail drawer with its event timeline and any captured
  payload. States its capability-unavailable reason (no `NOTIFICATIONS`, or no
  resolvable Core URL) rather than an empty list.
- **New SSE `rr` topic** — a signal topic (like `queues`), coalesced, on any
  flow state change.
- **Bounded/sampled payload capture** on `rr_event.detail` (already `JSONB`) —
  no new column.
- **Three ADRs (0030–0032).** Correlation strategy and its stated coverage
  ceiling; pooled Core connections (supersedes the per-call pattern
  `CoreMessageTransport` used, extending `core-transport`'s
  release-on-cluster-removal requirement to the pool); latency percentiles via
  Micrometer, deferring persisted history to Phase 6.

## Capabilities

### New Capabilities

- `request-reply-tracing`: expectations (which addresses to trace, and how),
  the notification-anchored/browse-sampled correlator and its six-state flow
  model, deadline resolution, sampled latency with its coverage disclosure, the
  flows/stats API, and the routed screen.

### Modified Capabilities

- `core-transport`: Core connections are pooled per cluster (`pooled-jms`)
  instead of opened and torn down per call; the existing requirement that
  connections are released on cluster removal and on shutdown now applies to
  the pool.
- `broker-events`: the normalised notification stream is delivered to more than
  one consumer; a failure in one consumer must not prevent delivery to another.
- `message-operations`: a browsed message carries its reply-to destination
  alongside its existing correlation id.

## Impact

- **Code (backend, new)**: `domain/rr/{Observation,FlowStateMachine,RrState}`,
  `broker/core/{RrSampler,CorePool}`, `service/{RrCorrelator,RrMetrics,
  RequestReplyService}`, `scheduler/RrDeadlineSweep`, `persist/{RrExpectation*,
  RrFlow*,RrEvent*}` (entities, repositories), `web/RequestReplyController`,
  `web/dto/RrViews`, `mapper/RrViewMapper`.
- **Code (backend, modified)**: `broker/core/CoreEventClient` (single sink →
  list, isolated dispatch), `broker/MessageBrowser` (`BrowsedMessage.replyTo`),
  `broker/{CoreMessageTransport,JolokiaMessageTransport}` (populate `replyTo`;
  `CoreMessageTransport` borrows from `CorePool` instead of opening per call),
  `config/ArtemisStudioProperties` (rr defaults: sweep interval, default
  deadline, percentile window, sample budget).
- **Code (frontend, new)**: `web/src/rr/{FlowsView,FlowsTable,StuckPanel,
  LatencyPanel,FlowDetail,ExpectationsView}.tsx` + tests; three new `--as-*`
  state tokens (in-flight / resolved / failed) in `web/src/theme.css`.
- **Code (frontend, modified)**: `web/src/router.tsx` (new `rr` route),
  `web/src/app/ClusterLayout.tsx` (nav entry), regenerated
  `web/src/api/schema.d.ts` and `web/openapi.json`.
- **APIs**: adds `GET/POST/PUT/DELETE /api/v1/clusters/{id}/rr/expectations`,
  `GET /api/v1/clusters/{id}/rr/flows{,/{flowId}}`, `GET
  /api/v1/clusters/{id}/rr/stats`; adds the `rr` SSE topic; message browse
  responses gain `replyTo`.
- **Schema**: one new changeset, `011-request-reply-keys.sql` (PK on
  `rr_event`; new columns + indexes on `rr_expectation`/`rr_flow`). Changesets
  001–010 untouched.
- **Dependencies (new)**: `org.messaginghub:pooled-jms` and
  `com.github.ben-manes.caffeine:caffeine` — both already version-managed by
  the `spring-boot-dependencies` BOM (3.2.2 / 3.2.4 respectively), declared
  versionless.
- **Config**: `application.yml` gains an `artemis-studio.rr` block (default
  deadline, sweep interval, percentile window, per-cluster sample budget).
- **Docs**: ADRs 0030–0032 added; `docs/broker-management-notes.md` gains §13;
  README Phase 5 rows ticked; `openspec/project.md` current-phase line updated.
