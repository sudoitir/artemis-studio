# ADR-0113: Plugins publish metrics through metric sources that Studio samples, stores, exports and alerts on

- **Status**: accepted
- **Date**: 2026-09-26
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/plugin-metrics`
- **Builds on**: [ADR-0006](0006-metrics-in-postgres.md), [ADR-0035](0035-alert-rule-model-and-evaluation-timing.md), [ADR-0055](0055-time-proportional-metric-charts.md), [ADR-0102](0102-the-plugin-api-is-published-to-central-and-npm.md)

## Context

Studio keeps queue metrics in `metric_sample` (ADR-0006), draws them on time-proportional charts (ADR-0055), and evaluates threshold rules on them right after the scrape tier that wrote them (ADR-0035). Micrometer's Prometheus registry serves `/actuator/prometheus`.

None of this reaches a plugin:
- The writer and every reader are fixed to `subject_type='QUEUE'`.
- `AlertCondition` beans are found in the main context only.
- Plugin contexts see only `@PluginApi` beans, and `MeterRegistry` is not one.

A plugin that runs work of its own therefore has three bad choices: publish nothing, keep a store of its own, or reach past the API.

## Decision

1. **A plugin names a metric and answers for it.** A new `@PluginApi` interface, `PluginMetricSource`, has two methods:
   - `String metric()`: the metric's identifier, `<plugin id>:<name>`.
   - `Map<String, Double> sample(UUID clusterId)`: the current value per subject.

   The plugin keeps whatever state it needs; Studio decides when to ask.
2. **Metrics are declared.** `plugin.json` `metrics` gives each metric a name, a description, a unit (`count`, `per_second`, `ms`, `ratio`), a subject label and a read permission of the plugin's own. The validator refuses unknown units and undeclared permissions. A source for an undeclared metric refuses activation. The declaration is what the rule form, the series endpoint and the permission check read, so none of them has to call plugin code.
3. **Studio asks on the scrape.**
   - When tier B completes for a cluster, Studio calls every source of every attached plugin for that cluster. Each call runs in the plugin (`runInPlugin`) on a virtual thread and waits at most 2 s.
   - The values go to `metric_sample` with `subject_type='PLUGIN'` and no node, which needs `node_id` to become nullable. They get the same partitions and reaper as queue samples.
   - The same values replace the rows of one Micrometer `MultiGauge` per metric (`studio.plugin.metric`, tags `plugin`, `metric`, `cluster`, `subject`). Prometheus therefore reads exactly what the time series holds.
   - Sampling runs before alert evaluation for the same tick.
4. **Rules use the existing machinery.**
   - `PluginMetricCondition` is one more `AlertCondition`. It reads the latest sampled values, so comparators, `forSeconds`, the state machine and channels are unchanged.
   - `AlertScope` gains `subjectPattern`.
   - `plugin.json` `alertRules` are seeded per cluster on attach and on cluster registration. `alert_rule_seed` records `(cluster, plugin, key)`, so a rule is created once and an operator's edit or deletion is never undone.
   - While the plugin is detached, its rules evaluate to nothing and are listed as "source unavailable".
5. **Reading and drawing.**
   - `GET /api/v1/clusters/{id}/metrics/plugin` returns gauge buckets for one metric and subject. It needs cluster read and the metric's declared permission.
   - The SDK exports `MetricChart` and `usePluginSeries`. The chart helpers they share with Studio's own charts move to `web/src/kernel/metrics`.

## Consequences

- One contribution gives a plugin a time series, charts, Prometheus and alerting, with nothing parallel to maintain on either side.
- Values are sampled on the tier-B cadence (15 s by default). A plugin that wants rates or percentiles computes them itself over its own window.
- A value is per Studio instance. Several instances write several samples for the same subject, and the charts average them within a bucket.
- A slow or failing source costs at most 2 s once per tick, and only its own values.
- The change is additive: new types and optional descriptor fields. `Contract.VERSION` stays 2.

## Alternatives considered

- **A plugin-scoped `MeterRegistry` plus a plugin `AlertSignalSource`.** Two hooks. Meters would reach Prometheus but not Studio's series or charts, and thresholds would live in plugin code instead of in operator-editable rules.
- **Plugins write `metric_sample` themselves.** It hands a plugin a table in `public`, which plugin isolation forbids, and couples plugins to its schema.
- **A timer of Studio's own for plugin sampling.** It would evaluate rules against values sampled at another time; the scrape tier already sets the cadence and the evaluation point.
