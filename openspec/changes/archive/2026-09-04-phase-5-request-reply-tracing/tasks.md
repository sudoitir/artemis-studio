## 1. Spike — broker behaviour (no product code)

- [x] 1.1 Add `RequestReplySpikeIT` extending `ArtemisIntegrationTest`: temp-reply-queue pattern, capturing every notification and its `_AMQ_*` properties in order
- [x] 1.2 Add the shared-reply-queue pattern case to the same test, plus a mid-flight `QueueBrowser` dump of every JMS header exposed (`JMSReplyTo`, `JMSCorrelationID`, `JMSMessageID`, `JMSExpiration`)
- [x] 1.3 Add the stuck-request case (no consumer, short `JMSExpiration`) and capture whether/how `MESSAGE_EXPIRED` fires
- [x] 1.4 Answer the seven design questions (design.md D1) from the captured output; write `docs/broker-management-notes.md` §13 with a verdict table and a "net changes to the plan" subsection
- [x] 1.5 If any verdict overturns a design decision (join key, temp-queue detection), update `design.md` and this task list before continuing

## 2. Schema

- [x] 2.1 Add changeset `011-request-reply-keys.sql`: primary key on `rr_event`
- [x] 2.2 Same changeset: `reply_address`, `correlation_property`, `capture_payload` on `rr_expectation`
- [x] 2.3 Same changeset: `observed_at`, `request_message_id`, `reply_message_id`, `responder_consumer`, `node_id` (+ FK) on `rr_flow`, plus `uq_rr_flow_request`, `ix_rr_flow_deadline`, `ix_rr_flow_open_reply`
- [x] 2.4 Verify the changeset against Testcontainers Postgres (`./mvnw verify` picks this up automatically)

## 3. Expectations (config, no tracing yet)

- [x] 3.1 JPA entities `RrExpectationEntity`, `RrFlowEntity`, `RrEventEntity` + repositories
- [x] 3.2 `RequestReplyService` expectation CRUD, enabled/disabled toggle
- [x] 3.3 `web/RequestReplyController`: `GET/POST/PUT/DELETE /clusters/{id}/rr/expectations`, each write wrapped in the same transaction as an `AuditService` event
- [x] 3.4 Capability gating: `rr` endpoints report the unavailability reason (no `NOTIFICATIONS`, no resolvable Core URL) instead of empty data
- [x] 3.5 Regenerate `web/openapi.json` / `web/src/api/schema.d.ts`
- [x] 3.6 Frontend `ExpectationsView.tsx` (CRUD form) + route `clusters/$clusterId/rr` shell showing the unavailable state
- [x] 3.7 Backend tests: expectation CRUD + audit trail; capability-gating test
- [x] 3.8 Frontend tests: `ExpectationsView` per the DOM harness, including the unavailable-capability render

## 4. Pooled Core connection + sampler (browse-only, logging sink)

- [x] 4.1 Add `pooled-jms` and `caffeine` dependencies to `pom.xml` (versionless, BOM-managed)
- [x] 4.2 `broker/core/CorePool`: `JmsPoolConnectionFactory` keyed by `(clusterId, coreUrl)`, built from `CoreConnectionFactory.build(...)`; evict on cluster removal
- [x] 4.3 Refactor `CoreMessageTransport.open()` to borrow from `CorePool` instead of building a factory/connection/session per call
- [x] 4.4 Add `replyTo` (and expose `messageId`) on `MessageBrowser.BrowsedMessage`; populate from both `CoreMessageTransport` and `JolokiaMessageTransport`
- [x] 4.5 `domain/rr/Observation` sealed interface (`RequestSeen`, `ReplySeen`, `ResponderUp`, `ResponderDown`, `TempQueueUnbound`, `MessageExpired`)
- [x] 4.6 `broker/core/RrSampler`: a `SchedulingConfigurer` trigger task (separate from `ScrapeScheduler`'s Jolokia tiers) that browses page 1 of each enabled expectation's request (and reply, if configured) address via the pooled Core connection, capped depth, per-cluster sample budget; emits `Observation`s to a logging no-op sink
- [x] 4.7 Backend tests: `CorePoolTest` (reuse across calls), `RrSamplerTest` against `ArtemisIntegrationTest` (real requests observed)
- [x] 4.8 `CoreMessageTransportTest` updated for the pooled path

## 5. Correlator and state machine (browse-only flows)

- [x] 5.1 `domain/rr/FlowStateMachine`: pure `switch`-based transition function per design.md D2/D3, table-driven-testable
- [x] 5.2 `service/RrCorrelator`: creation path (`onRequestSeen`), reply-join path (`onReplySeen`, both JMS conventions + temp-queue destination, oldest-first), Caffeine hot-key cache in front of `RrFlowRepository`
- [x] 5.3 Deadline resolution (`deadlineAt`): message expiration → expectation deadline → system default (`artemis-studio.rr.default-deadline-ms`)
- [x] 5.4 `scheduler/RrDeadlineSweep`: scheduled native `UPDATE ... RETURNING`, `ORPHANED` vs `TIMED_OUT` split on `responder_consumer`
- [x] 5.5 `rr_event` batched writer for `REQUEST_SEEN`/`REPLY_SEEN`/state-transition events (mirror `BrokerEventWriter`'s buffered-batch pattern)
- [x] 5.6 Wire `RrSampler`'s observations to `RrCorrelator` (replace the slice-4 logging sink)
- [x] 5.7 `GET /clusters/{id}/rr/flows` and `GET /clusters/{id}/rr/flows/{flowId}` (paged, filterable by state/address/correlationId/time range; single-flow read includes its `rr_event` timeline)
- [x] 5.8 Backend tests: `FlowStateMachineTest` (every state × observation pair, including no-ops), `RrCorrelatorTest`, `RrDeadlineSweepTest` against `PostgresIntegrationTest`

## 6. Notification-driven observations

- [x] 6.1 Refactor `broker/core/CoreEventClient` to accept `List<BrokerEventSink>` and dispatch to each, catching and logging per-sink failures
- [x] 6.2 `service/RrNotificationObserver` implementing `BrokerEventSink`: maps `BrokerEvent` → `ResponderUp`/`ResponderDown`/`TempQueueUnbound`/`MessageExpired`, forwards to `RrCorrelator`
- [x] 6.3 Backend tests: `CoreEventClientTest` updated for fan-out + isolation; `RrNotificationObserverTest`; an `ArtemisIntegrationTest`-based test proving `RESPONDER_DROPPED` and the temp-queue path resolve from push events

## 7. Metrics, coverage, and realtime

- [x] 7.1 `service/RrMetrics`: Micrometer `Timer` per `(clusterId, address)`, `publishPercentiles(0.5, 0.95, 0.99)`, time-windowed; record on `COMPLETED`
- [x] 7.2 Coverage ratio: observed-flows-in-window vs. `queue_snapshot` message-added delta for the address; "coverage unknown" when snapshot history is insufficient
- [x] 7.3 `GET /clusters/{id}/rr/stats`: per-address in-flight/oldest-in-flight/terminal-state counts + percentiles + `sampled`/`coverageRatio`/`windowMs`
- [x] 7.4 New SSE `rr` topic wired through the existing `SseHub` + `TopicCoalescer`, published on any flow state change (sweep and correlator)
- [x] 7.5 Backend tests: `RrMetricsTest`, `RequestReplyControllerTest` (`/rr/stats` shape, coverage-unknown case), SSE `rr` topic test mirroring `StreamControllerTest`

## 8. Frontend — the flagship screen

- [x] 8.1 Three new `--as-*` semantic tokens in `web/src/theme.css` (in-flight / resolved / failed)
- [x] 8.2 `FlowsTable.tsx` via the existing `web/src/grid` helpers: state badge, address, correlation id, age, latency
- [x] 8.3 `StuckPanel.tsx`: awaiting-reply past 50% of deadline, plus terminal non-completed states, oldest first
- [x] 8.4 `LatencyPanel.tsx`: `@mantine/charts`, p50/p95/p99 per address, sampling banner + coverage ratio shown next to the chart (not a tooltip)
- [x] 8.5 `FlowDetail.tsx`: drawer with the `rr_event` timeline and captured payload preview, modelled on `MessageDetailPanel`
- [x] 8.6 `FlowsView.tsx`: route shell with Flows / Stuck / Latency / Expectations tabs, filters in the URL, subscribes to the `rr` SSE topic
- [x] 8.7 Add the `rr` route to `web/src/router.tsx` and a nav entry in `web/src/app/ClusterLayout.tsx`
- [x] 8.8 Frontend tests per ADR-0024 for each new component, including the capability-unavailable render

## 9. Payload capture, retention, docs, close-out

- [x] 9.1 Truncated request/reply body + properties capture on `rr_event.detail`, gated by `rr_expectation.capture_payload`, size cap in `artemis-studio.rr` settings
- [x] 9.2 `RrFlowReaper`: scheduled retention drop for old flows/events, following `BrokerEventReaper`'s pattern
- [x] 9.3 `artemis-studio.rr` settings block in `application.yml` (default deadline, sweep interval, percentile window, sample budget, payload cap)
- [x] 9.4 Write ADR-0030 (correlation strategy + coverage ceiling), ADR-0031 (pooled Core connections, supersedes the per-call pattern), ADR-0032 (Micrometer latency percentiles, defers history to Phase 6)
- [x] 9.5 Tick off Phase 5 rows in `README.md`; update `docs/roadmap.md` if needed; update `openspec/project.md` current-phase line
- [x] 9.6 Full `just verify` pass (Spotless, Liquibase-vs-Testcontainers-Postgres, backend + frontend tests, `OpenApiSnapshotTest` regenerated)
- [x] 9.7 Manual end-to-end pass per plan's Verification section against `just up` / `just dev`: completed, responder-dropped, and orphaned flows all observed live in the UI
