# ADR-0032: Request-reply latency via Micrometer time-windowed percentiles, no persisted history in Phase 5

- **Status**: accepted
- **Date**: 2026-09-04
- **Deciders**: Mahdi Amirabdollahi

## Context

`rr_flow.latency_ms` is recorded per completed flow, but the flows screen needs
p50/p95/p99 per traced address, not a per-row scan. `metric_sample` (ADR-0006)
is Phase 6's general broker-metrics time series and is explicitly out of scope
for this phase (design.md Non-Goals) — building persisted percentile history
now would duplicate work Phase 6 already owns.

## Decision

**A Micrometer `Timer` per `(clusterId, address)`**, tagged and registered via
the existing `MeterRegistry` bean (`spring-boot-starter-actuator` +
`micrometer-registry-prometheus`, both already on the classpath — no new
dependency). `RrMetrics.recordCompletion` records into it on every `COMPLETED`
transition; `publishPercentiles(0.5, 0.95, 0.99)` with
`distributionStatisticExpiry(artemis-studio.rr.percentile-window)` gives a
correctly time-windowed rolling percentile without Studio computing quantiles
by hand. The same meters are exported at `/actuator/prometheus` for free.

**Percentiles are never returned without a coverage estimate alongside them**
(ADR-0030's disclosure requirement). `RrMetrics.stats()` pairs each address's
percentiles with a coverage ratio: the count of flows observed to complete in
the window divided by the delta in `queue_snapshot.messages_added` for that
address over the same window. This is a **live best-effort estimate, not a
persisted metric** — a single in-process baseline reading per address,
recomputed on each `/rr/stats` call, not a time series. The first call for an
address has no prior baseline and reports `coverageRatio: null` ("unknown")
rather than guessing.

## Consequences

- No new schema, no new dependency, no duplicated history mechanism against
  Phase 6.
- Coverage does not survive an application restart (the baseline is
  in-memory) and is only as good as `queue_snapshot`'s own freshness — both are
  acceptable for a live estimate whose entire purpose is disclosure, not
  billing-grade accuracy.
- If a future deployment needs coverage numbers that survive a restart, the
  upgrade path is Phase 6's real metric history — this ADR does not block that.

## Alternatives considered

- **Persist rr latency into `metric_sample` now** — rejected: `metric_sample`
  is Phase 6's concern (ADR-0006); pulling it forward duplicates schema and
  retention work for a feature this phase does not need history for.
- **Hand-rolled percentile computation over `rr_flow.latency_ms`** — rejected:
  Micrometer's `Timer` already does time-windowed percentile aggregation
  correctly and exports to Prometheus with zero extra code; recomputing it by
  scanning rows on every stats call would also scale worse.
- **Compute coverage from a real historical snapshot table** — rejected for
  this phase: `queue_snapshot` is a current-value cache (ADR-0016), not a time
  series; building one to serve a "nice to have" coverage number would be the
  same work as Phase 6's `metric_sample`, just done early and for one narrow
  use.
