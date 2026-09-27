## 1. Contract and descriptor

- [x] 1.1 ADR-0113 and this change
- [x] 1.2 `PluginMetricSource` (`@PluginApi`); `plugin.json` `metrics` and `alertRules` in `PluginDescriptor`, the schema and `PluginValidator` (namespace, unit, declared permission, rules on declared metrics)
- [x] 1.3 `PluginMetrics` bridge: registers a plugin's sources on attach (undeclared metric refuses activation), drops them on detach

## 2. Sampling

- [x] 2.1 Changeset `platform-scrape-0002`: `metric_sample.node_id` nullable
- [x] 2.2 Tier-B sampling: each source called in the plugin, time-boxed at 2 s, failures isolated; rows written as `PLUGIN`; latest values kept for alerting
- [x] 2.3 `studio.plugin.metric` `MultiGauge` per metric, rows replaced on every sample and removed on detach

## 3. Alerting

- [x] 3.1 `PluginMetricCondition`; `AlertScope.subjectPattern`; rules on a detached plugin's metric evaluate to nothing and show "source unavailable"
- [x] 3.2 Changeset `feature-alerting-0003` (`alert_rule_seed`); declared rules seeded per cluster on attach and on cluster registration, once
- [x] 3.3 `GET …/alerts/plugin-metrics`; the rule form lists plugin metrics with description and unit; the rule list marks unavailable ones

## 4. Reading series

- [x] 4.1 `GET …/metrics/plugin` with the declared permission
- [x] 4.2 Chart helpers move to `kernel/metrics`; SDK exports `MetricChart` and `usePluginSeries`

## 5. Kit and tests

- [x] 5.1 Template example metric and seeded rule; plugin guide "Metrics and alerts"
- [x] 5.2 Tests: validator refusals; sampled into `metric_sample` and Prometheus; a threshold rule fires and resolves; seeding once, keeping edits and deletions; detach stops sampling; series permission enforced
- [x] 5.3 `just verify` green
