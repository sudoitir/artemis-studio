## 1. Decision record

- [x] 1.1 `docs/adr/0053-broker-time-normalised-and-skew-measured.md`, referencing
      ADR-0030, ADR-0035 and ADR-0045.
- [x] 1.2 `docs/adr/README.md` index row.

## 2. Clock measurement

- [x] 2.1 `Clock` bean, injected into the request-reply and skew path only.
- [x] 2.2 Model Jolokia's discarded `timestamp` on `JolokiaResponse`.
- [x] 2.3 `ClockOffsetRegistry` — min-RTT selection, EWMA, decaying best RTT,
      `uncertaintyMs = rtt/2 + 500`. Written by `JolokiaBrokerClient`, shared by
      `BrokerClientFactory`; no call site changes, no extra request.
- [x] 2.4 `BrokerTime.toStudioTime`; a sub-uncertainty offset is not a correction.
- [x] 2.5 `ClockOffsetService` — join readings to nodes, persist, and decide
      (`UNKNOWN` / `IN_AGREEMENT` / `BROKER_SKEWED` / `STUDIO_SUSPECT`).
- [x] 2.6 `MonotonicClockWatch` — a stepped wall clock discards every estimate.
- [x] 2.7 Both registered in `DynamicSchedules`.
- [x] 2.8 `020-clock-skew-and-latency-source.sql` + master include.

## 3. Normalisation and latency policy

- [x] 3.1 `RrCorrelator.deadlineAt` — the reported bug.
- [x] 3.2 `NotificationMapper` — notification instants onto the same timeline.
- [x] 3.3 `Observation.RequestSeen` / `ReplySeen` carry a normalised `enqueuedAt`;
      the notification path passes none.
- [x] 3.4 `FlowStateMachine` chooses `MESSAGE_TIMESTAMPS` or `OBSERVED`; a negative
      result is never stored as a latency.
- [x] 3.5 Forward-only request and reply skew, with a `CLOCK_SKEW` flow event.
- [x] 3.6 `rr.sample-interval` pushed to the correlator as the observed error bar.

## 4. Alerting

- [x] 4.1 `CLOCK_SKEW` in `StateCondition`, subject-keyed per node plus `studio`.
- [x] 4.2 Accepted by `AlertRuleService`, seeded per cluster at `WARNING`.
- [x] 4.3 `STATE_CONDITIONS` and the label map in `web/src/alerts/severity.ts`.

## 5. Sampler and diagnostics

- [x] 5.1 `RrSamplerHealth` — per-expectation account of the last tick, in memory.
- [x] 5.2 `QueueTargetResolver` — queue name and routing type from the last scrape.
- [x] 5.3 Empty serving-node set becomes a stated reason and a throttled warning.
- [x] 5.4 `samplePerMin` honoured; a rate above the global floor is disclosed.
- [x] 5.5 `GET /clusters/{id}/rr/diagnostics` + `RrViews` DTOs + ranked reasons.
- [x] 5.6 `FlowView` gains `latencySource`, `latencyBoundMs`, both enqueue instants
      and both skews.

## 6. Interface

- [x] 6.1 `web/src/rr/TracingDiagnostics.tsx` — the flows empty state and the
      shared per-expectation status.
- [x] 6.2 `FlowsView` renders it instead of `0 flows`.
- [x] 6.3 `ExpectationsView` gains a Status column.
- [x] 6.4 `FlowDetail` discloses the latency source, its bound, and any skew.

## 7. MCP

- [x] 7.1 `trace_request_reply` gains `mode=diagnostics`; no new tool, no new
      parameter.
- [x] 7.2 `McpToolDetail` lists the new discriminator value.
- [x] 7.3 `cluster_health` result gains `asOf` and a `clock` block.
- [x] 7.4 `triage_cluster` prompt mentions the clock check.
- [x] 7.5 `McpToolSchemaBudgetTest` still green.

## 8. Tests

- [x] 8.1 `ClockOffsetRegistryTest`, `BrokerTimeTest`, `ClockOffsetServiceTest`.
- [x] 8.2 `FlowStateMachineTest` — latency source selection and refusal.
- [x] 8.3 `RrCorrelatorTest` — the deadline regression test.
- [x] 8.4 `RrSamplerTest` — no-Core-endpoint reason and the `samplePerMin` throttle.
- [x] 8.5 `TracingDiagnostics.test.tsx` — the explained empty state.

## 9. Close-out

- [x] 9.1 Regenerate `web/openapi.json` and `web/src/api/schema.d.ts`.
- [x] 9.2 `just verify` green.
- [x] 9.3 Commit message — `fix(rr):` / `feat(rr):`, body for someone upgrading.
