## 1. Decision record

- [x] 1.1 Write `docs/adr/0089-consumer-health-verdict-ladder.md` (Nygard style, per `docs/adr/000-template.md`): the ordered ladder and its precedence, why it is fixed rather than a configurable rule DSL, why `deliveringCount` is the root-cause discriminator, and its subordination to ADR-0044. Reference 0033, 0035, 0069, 0070, 0074.
- [x] 1.2 Add the entry to `docs/adr/README.md`.

## 2. Sampling — two more metrics, no schema change

- [x] 2.1 `platform/scrape/MetricSampleWriter.appendQueueSamples` also appends `deliveringCount` and `messagesExpired` from the existing `QueueRow`; update the class doc comment to say six samples per queue per tick, and note that no broker call is added.
- [x] 2.2 `feature/metrics/MetricQueryService`: add `deliveringCount` to `GAUGE_METRICS` and `messagesExpired` to `RATE_METRICS`.
- [x] 2.3 `feature/metrics/MetricsModule`: add both names to the `metric_series` tool's `values` list.
- [x] 2.4 Note the +50% row volume in `platform/scrape/MetricProperties`' retention doc comment.
- [x] 2.5 Test: a sweep writes six rows per queue, and both new names round-trip through `MetricQueryService` with the correct kind (gauge averaged with a peak; counter as a restart-clamped rate).

## 3. Depth slope

- [x] 3.1 `platform/scrape/MetricSamples`: add `depthSlopeBySubject(clusterId, from, to)` using `regr_slope(value, extract(epoch from ts))`, grouped per subject and node then summed, `HAVING count(*) >= 2`, following `latestRateWithTimeBySubject`'s shape and its `Timestamp.from(...)` parameter discipline.
- [x] 3.2 Test against Testcontainers Postgres: a steadily climbing series yields a positive slope, a flat one ~0, an oscillating one ~0, and a subject with one sample is absent rather than zero.

## 4. The verdict service

- [x] 4.1 `feature/triage/ConsumerHealth`: the verdict enum with its severity rank, and the evidence record (depth, slope, add/ack/net rate, per-consumer rate, delivering, scheduled, drain ETA, sample `asOf` + `span`, nodesPresent/nodesTotal, stale, derived-vs-broker source, cause notes).
- [x] 4.2 `feature/triage/ConsumerHealthProperties`: `idle-ack-rate` (default `0.01`), `min-backlog` (default `1`), window default `2 × tierBInterval`. Register in the same way other `@ConfigurationProperties` records are.
- [x] 4.3 `feature/triage/ConsumerHealthService`: the ladder, first-match-wins, reusing `CrossNodeAggregator.queues(...)`, `QueueSnapshots.forCluster(...)`, `MetricSamples.latestRateWithTimeBySubject(...)` for `messagesAdded`/`messagesAcked`, the new slope read, and `BrokerEventService` for recent `CONSUMER_SLOW`. Call `ClusterAccessGuard.requireCluster(id, CLUSTER_READ)` **before** input validation, as `MetricQueryService` does.
- [x] 4.4 Rank and page through `kernel.core`'s `ResourceQuery` / `PagedView`; support a single-queue lookup for the drawer.
- [x] 4.5 `feature/triage/package-info.java`: add `platform.scrape` and `kernel.core` to `allowedDependencies`.
- [x] 4.6 Tests: one per ladder branch; `INSUFFICIENT_DATA` for fewer than two samples must not yield `HEALTHY`; `BROKER_SLOW` overrides a derived `FALLING_BEHIND` (ADR-0044); `PAUSED` outranks `NO_CONSUMERS`; `STALLED` vs `STARVED` turn only on `deliveringCount`; a partially-present queue reports its node coverage.

## 5. REST surface

- [x] 5.1 `feature/triage/web/TriageViews`: the response DTOs.
- [x] 5.2 `feature/triage/web/TriageController`: `GET /api/v1/clusters/{clusterId}/consumer-health` with `window`, `q`, `queue`, `sort`, `page`, `size`.
- [x] 5.3 `feature/triage/TriageModule`: add the `apiPrefix` to the descriptor.
- [x] 5.4 Regenerate `web/src/kernel/api/schema.d.ts`.
- [x] 5.5 Test: ranked worst-first by default; `?queue=` returns one; a caller without `cluster:read` is denied without revealing whether the cluster exists.

## 6. MCP becomes a thin adapter

- [x] 6.1 `feature/triage/mcp/TriageMcpTools`: delete `trend()` and `slowConsumerVerdict()`; have `diagnose(clusterId, queue)` delegate to `ConsumerHealthService` and return the typed verdict and evidence.
- [x] 6.2 Update `TriageModule`'s `diagnose` tool description to match what it now returns.
- [x] 6.3 Test: the MCP diagnosis and the REST response report the same verdict and evidence for the same queue; an under-sampled queue reports uncomputed rather than healthy.

## 7. Alerting

- [x] 7.1 Make the extension point real: add `boolean supports(AlertRuleSpec rule)` to `AlertCondition` (the spec projection, not a bare metric string — `StateCondition` keys off `isThreshold()`, and the entity is not exported), promoting the `static supports` each implementation already carries; leave `GaugeCondition`, `RateCondition`, `SlowConsumerCondition` and `StateCondition` behaviour unchanged.
- [x] 7.2 `feature/alerting/AlertEvaluator`: inject `List<AlertCondition>` in place of the four named fields; `conditionFor` selects the first whose `supports` matches, with explicit ordering (`@Order`) that preserves today's precedence — state conditions first, then derived metrics, then gauge, then rate. Keep the existing "unrecognised metric" warning for no match.
- [x] 7.3 Verify the four existing condition tests pass unchanged; this refactor must be behaviour-preserving before anything is added to it.
- [x] 7.4 `feature/triage/HealthVerdictCondition` implementing `AlertCondition`, `METRIC = "consumerHealth"`, value = severity rank, following `SlowConsumerCondition`'s structure. It lives in `triage`, **not** `alerting`: triage already depends on alerting, so the reverse edge would be a cycle (design.md decision 6). `INSUFFICIENT_DATA` subjects are excluded from `universe` entirely.
- [x] 7.5 `web/src/features/alerting/severity.ts`: add `consumerHealth` to `DERIVED_METRICS`, its label, and its "what this actually watches" help text; the rule form renders a severity select, never a bare rank.
- [x] 7.6 Tests: fires at or above the selected severity after debounce; resolves on recovery; a paused queue does not fire; an `INSUFFICIENT_DATA` queue neither fires nor resolves a live firing. Confirm `ModularityTest` still passes — no `alerting → triage` edge exists.

## 8. Frontend

- [x] 8.1 `web/src/features/triage/api.ts`: query keys and hooks, DTOs from the generated `kernel/api/schema.d.ts`.
- [x] 8.2 `web/src/features/triage/verdict.ts` + `HealthVerdict.tsx`: verdict → word, severity → existing `--as-*` token. No new tokens; no colour on healthy.
- [x] 8.3 `web/src/features/triage/ConsumerHealthView.tsx`: ranked table on `ui/VirtualTable.tsx` + `ui/Pager.tsx`; filter, sort and page URL-owned; distinct empty, filtered-empty and unreachable-is-not-empty states; tabular figures on numeric columns.
- [x] 8.4 `web/src/features/triage/QueueHealthPanel.tsx`: the `queue.detail.panels` contribution (order below the metrics panel's 10), linking to `/metrics?subject=<queue>` rather than redrawing history.
- [x] 8.5 `web/src/features/triage/feature.ts`: `defineFeature({ id: 'triage' })` — route `consumer-health` under `clusterRoute` wrapped in `featureView`, nav `{ group: 'observe', order: 18, label: 'Consumer health', permission: 'cluster:read' }`, the slot contribution, and a `queues` stream topic to invalidate on sweep.
- [x] 8.6 Register in `web/src/app/features.ts` and add `'triage'` to `FEATURE_IDS`.
- [x] 8.7 Tests (`src/test/render.tsx`, by role and accessible name only): the ranked view renders worst-first; an uncomputed verdict is distinguishable from healthy; `renderAppAt('/clusters/:id/consumer-health')` resolves; the drawer panel appears in the queue drawer.
- [x] 8.8 Verify verdict text contrast ≥ 4.5:1 in **both** schemes, measuring the light scheme independently.

## 9. Close out

- [x] 9.1 Regenerate `docs/modules/`.
- [x] 9.2 Update `docs/architecture.md` where it maps the Observe views and the scraped metric set.
- [x] 9.3 Tick `A · Consumer health` in the `README.md` roadmap.
- [x] 9.4 `just fmt` then `just verify` — Spotless, Liquibase against Testcontainers PG, `ModularityTest`, `BoundaryRulesTest`, backend and frontend suites all green.
- [x] 9.5 End-to-end on `just dev-up`: produce with no consumer → `NO_CONSUMERS`; a consumer that never acks → `STALLED` (delivering > 0); a non-matching selector → `STARVED` (delivering = 0); drain it → `DRAINING` with an ETA.
- [x] 9.6 Confirm no new Jolokia request was introduced by the sweep change.

## Notes from apply

Two things were discovered while building and are recorded here rather than left implicit:

- **`AlertCondition` had to take a projection, not the entity.** `AlertRuleEntity` lives in
  `feature.alerting.internal.persistence`, which Spring Modulith does not export, so a
  condition in another module could not have implemented the old interface at all. The new
  `AlertRuleSpec` is what makes the extension point real. This also fixed a pre-existing
  bug: `AlertRuleService.validated()` hard-coded gauge and rate metrics, so an
  `ackRatePerConsumer` rule — which ADR-0044 ships a template for — could never be saved
  through the API. Both derived metrics now create successfully.
- **The guard had to move off the scheduler path.** Alerting evaluates on a scrape thread
  with no authenticated principal, so calling through `ClusterAccessGuard` failed as a 404
  ("cluster does not exist") on every tick while the REST path looked fine. The guard now
  sits at the request boundary (`page`/`forQueue`) and the shared evaluation is unguarded,
  matching how the other conditions read `queue_snapshot`. Caught only by the end-to-end
  run in 9.5; `HealthVerdictConditionTest` now runs unauthenticated so it cannot regress.

The `STALLED` / `STARVED` / `NO_CONSUMERS` / `DRAINING` ladder rungs, the worst-first
ranking, and a firing `consumerHealth` alert were all confirmed against the real broker
stack in 9.5.
