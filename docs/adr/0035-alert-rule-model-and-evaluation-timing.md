# ADR-0035: Alert rules are a discriminated union; evaluation rides the scrape

- **Status**: accepted
- **Date**: 2026-09-05
- **Deciders**: Mahdi Amirabdollahi

## Context

`006-alerting.sql` (Phase 1) scaffolded `alert_rule` as a pure metric threshold
(`metric`, `comparator`, `threshold`, all `NOT NULL`) "populated from Phase 7." By
Phase 7, ADR-0012 had already established that split-brain, node-down, and
replication-desync — three of README's five alerting tasks — are *state
transitions* derived by `HaStateEvaluator`/`ScrapeCycle`/`SplitBrainRegistry`, not
values that ever land in `metric_sample` (`MetricSampleWriter` only ever writes
`subject_type='QUEUE'`). The scaffolded schema had no shape for that.

Separately, Phase 6's `MetricQueryService`/`MetricSeriesRepository` established
that `queue_snapshot` (current gauge values, refreshed every tier-B/C tick) and
`metric_sample` (bucketed history) are different tools for different questions;
an alert evaluator needs "the value right now," which is `queue_snapshot`'s job
for gauges, but needs "the rate over a recent window" for counters, which only
`metric_sample` can answer.

ADR-0015 established the tiered scrape scheduler (`ScrapeScheduler`, tiers A/B/C)
and its rule that broker network I/O never shares a transaction with
persistence. Any alert evaluator has to fit inside that shape without becoming
a second source of broker load or a second scheduling model to reason about.

## Decision

We will make `alert_rule.kind` a discriminated union — `METRIC_THRESHOLD` or
`STATE` — enforced by a database CHECK requiring exactly one shape's columns to
be populated, with one shared evaluator and one shared OK→PENDING→FIRING
debounce state machine for both kinds.

- **One condition interface, three implementations.** `GaugeCondition` reads
  `queue_snapshot` for `messageCount`/`consumerCount`/`deliveringCount`/
  `scheduledCount`. `RateCondition` reads a new batched
  `MetricSeriesRepository.latestRateBySubject(clusterId, metric, from, to)` for
  `messagesAdded`/`messagesAcked` — one query per `(cluster, metric)` per tick,
  not one per rule — over a 2×tier-B window, using the same
  `GREATEST(max-min,0)` restart-safe math ADR-0033 already uses for chart rates.
  A subject with fewer than two samples in that window is omitted from the
  result entirely, never reported as a rate of zero — a freshly-scraped queue
  must not read as "throughput dropped to zero." `StateCondition` reads
  `HaStateEvaluator`/`SplitBrainRegistry`/`broker_node` directly for a closed set
  of four conditions (`SPLIT_BRAIN`, `NODE_DOWN`, `REPLICATION_BEHIND`,
  `CLUSTER_DEGRADED`); `SPLIT_BRAIN` fires only on a corroborated `CRITICAL`
  verdict, never on the first-sighting `SUSPECTED` one, per ADR-0012.
- **Evaluation is triggered inline from `ScrapeScheduler`, not an independent
  timer.** State-condition rules evaluate right after tier A persists HA state;
  metric-threshold rules evaluate right after tier B/C persist queue snapshots
  and metric samples for a cluster. There is no fourth `@Scheduled` bean and no
  new polling cadence to configure. Evaluation is DB-only and runs after the
  scrape's network I/O has completed, so it never shares a transaction with a
  broker call.
- **The `for_seconds` debounce is a persisted `since` timestamp on
  `alert_state`, not an in-memory timer.** Each tick recomputes the *entire*
  current subject universe for a rule; a tracked subject absent from that
  universe (a deleted queue) resolves rather than being left stuck.

## Consequences

- Alert latency equals scrape latency — an honest bound, not a claimed
  guarantee the underlying data can't back up.
- The debounce survives a restart with zero in-memory state, at the cost of one
  extra row read/write per active `(rule, subject)` per tick.
- Threshold rules are queue-scoped only; there is no address- or broker-level
  metric to alert on until `MetricSampleWriter` writes such rows.
- Adding a fifth state condition is one CHECK constraint change plus one
  `switch` arm in `StateCondition` — no schema redesign.
- A cluster with an unusually slow scrape tier (many nodes, rate-limiter
  contention) evaluates its alerts less often. This tracks the data's own
  timeliness rather than exceeding it, which we judge the correct trade-off.

## Alternatives considered

- **A separate `ha_alert_rule`/state-alerting feature, forked from metric
  alerting.** Rejected — the debounce, firing history, delivery batching,
  routing, and UI are identical for both kinds; forking them duplicates five
  subsystems for a difference that is one method (`AlertCondition.evaluate`)
  wide.
- **Route every threshold rule through `MetricQueryService`'s bucketed
  `date_bin` reads, uniformly.** Rejected for gauges — it is a *history* read
  with epoch-alignment overhead, over an index shaped for
  `(cluster_id, subject_type, subject_name, metric, ts)` rather than
  "latest value," and could read up to one tier-B interval stale for no
  benefit over `queue_snapshot`'s current-state index.
- **An independent `scheduler/AlertEvaluationSweep`, mirroring
  `RrDeadlineSweep`.** Rejected — an independently-timed sweep against a
  differently-timed scrape tier sees zero, one, or two new snapshots per tick
  depending on phase drift, which jitters the `for_seconds` debounce for
  reasons that have nothing to do with the condition itself.
- **An in-memory debounce timer**, matching `ScrapeCycle`'s split-brain
  ratchet. Rejected — that ratchet's worst restart case is one extra detection
  cycle (accepted in ADR-0012); losing `for_seconds` progress on every restart
  would make a configured debounce interval meaningless across routine deploys.
