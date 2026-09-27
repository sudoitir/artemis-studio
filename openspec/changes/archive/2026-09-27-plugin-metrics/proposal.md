## Why

A plugin that processes messages has numbers an operator needs to watch, such as how often it runs, how often it fails and how slowly it answers. Today it has no sanctioned way to publish them:
- Studio's time series, its charts and its alert rules cover queues only.
- The Prometheus registry is not open to plugins.
- The alerting extension points are host beans that a plugin's context never reaches.

A plugin author is left to build a parallel store, a parallel chart and a parallel alerting path, and operators get a second place to look.

## What Changes

- A new `@PluginApi` interface, `PluginMetricSource`: a plugin bean that names one metric and returns, for a cluster, a value per subject (for example per job or per connection).
- `plugin.json` gains `metrics` (name, description, unit, subject, read permission) and `alertRules` (default rules on those metrics). A source bean for an undeclared metric refuses activation.
- On every tier-B scrape, Studio samples each attached plugin's sources:
  - Each call is isolated and time-boxed.
  - The values are stored in `metric_sample` as subject type `PLUGIN`, with the same retention as queue series.
  - They are exposed on `/actuator/prometheus` as `studio_plugin_metric{plugin,metric,cluster,subject}`.
- Threshold alert rules accept a plugin metric. They use the existing comparators, `for` duration, severities and channels. A rule's scope can narrow it to subjects by pattern.
- A plugin's declared rules are created once per cluster when the plugin first attaches, and for clusters registered later. An operator's edit or deletion is never undone. While the plugin is not running, its rules show "source unavailable" and do not fire.
- `GET /api/v1/clusters/{id}/metrics/plugin` reads a plugin metric's series. It needs cluster read and the permission the metric declares. `GET /api/v1/clusters/{id}/alerts/plugin-metrics` lists the metrics a rule can use, and the rule form offers them.
- The UI SDK exports `MetricChart` and `usePluginSeries`, so a plugin draws Studio's own time-proportional chart without bundling a chart library.
- The plugin template publishes an example metric, and the plugin guide gains "Metrics and alerts".

## Capabilities

### New Capabilities
- `plugin-metrics`: plugins publish metrics that Studio samples, stores, charts, exposes to Prometheus and alerts on.

### Modified Capabilities
- None.

## Impact

- Additive `@PluginApi` type and descriptor fields; `Contract.VERSION` stays 2. Decided in ADR-0113; builds on ADR-0006, ADR-0035, ADR-0055 and ADR-0102.
- Changeset `platform-scrape-0002` lets `metric_sample.node_id` be null, because a plugin sample belongs to no broker node. Changeset `feature-alerting-0003` adds `alert_rule_seed`, which records the rules Studio has seeded.
- The metric chart helpers move from `features/metrics` to `kernel/metrics`, so the SDK can export them.
