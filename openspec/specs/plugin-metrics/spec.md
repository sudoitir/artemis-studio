# plugin-metrics Specification

## Purpose
The metrics a runtime plugin publishes: how it declares them, how Studio samples them into its time series and Prometheus, and how alert rules and permissions apply to them.

## Requirements

### Requirement: A plugin declares the metrics it publishes

A plugin's descriptor SHALL be able to declare metrics. Each metric has:
- a name, unique in the plugin
- a description
- a unit: `count`, `per_second`, `ms` or `ratio`
- the kind of subject its values are keyed by
- the plugin permission needed to read its series

Its full identifier SHALL be `<plugin id>:<name>`. The system SHALL refuse a plugin whose metric names a unit it does not know or a permission the plugin does not declare. At activation it SHALL refuse a plugin with a metric source for a metric it did not declare.

#### Scenario: An undeclared metric refuses activation
- **WHEN** a plugin defines a metric source for `acme-notes:edits` without declaring it
- **THEN** its activation fails with a message naming the metric

#### Scenario: A metric needs a declared permission
- **WHEN** a plugin declares a metric readable with `acme-notes:stats` but does not declare that permission
- **THEN** it is refused at upload with the reason

### Requirement: Studio samples plugin metrics into its time series and Prometheus

On every tier-B scrape of a cluster, the system SHALL ask each running plugin's metric sources for that cluster's values. It SHALL store every value in its time series with the same retention as queue metrics, and SHALL expose it on its Prometheus endpoint as `studio_plugin_metric` with the labels `plugin`, `metric`, `cluster` and `subject`. A source that fails or takes longer than two seconds SHALL be skipped for that scrape without delaying the other sources or the scrape. A plugin's metrics SHALL stop being sampled and exported when it stops.

#### Scenario: A plugin metric reaches the time series and Prometheus
- **WHEN** a running plugin's source returns 3 for subject `daily` on a cluster
- **THEN** the cluster's time series holds that sample for `acme-notes:edits` and `daily`, and Prometheus shows it with those labels

#### Scenario: A slow source does not hold up the scrape
- **WHEN** a source blocks
- **THEN** it is skipped after two seconds, the other sources are sampled, and the scrape goes on

### Requirement: Alert rules can watch plugin metrics

A threshold alert rule SHALL be able to use a running plugin's metric with the existing comparators, duration, severities and channels. Its scope SHALL be able to narrow the rule to subjects by pattern. A plugin's declared default rules SHALL be created once per cluster, when the plugin first runs and for clusters registered later. An operator's change to a seeded rule, or its deletion, SHALL never be undone. While a plugin is not running, rules on its metrics SHALL NOT fire and SHALL show that their source is unavailable.

#### Scenario: A rule on a plugin metric fires and resolves
- **WHEN** a rule `acme-notes:edits > 5` holds for its duration and later stops holding
- **THEN** an alert fires and resolves through the rule's channels

#### Scenario: A deleted seeded rule stays deleted
- **WHEN** an operator deletes a seeded rule and the plugin is updated
- **THEN** the rule is not created again

### Requirement: Plugin series are readable with the metric's permission

The system SHALL return a plugin metric's series for one subject over a window, in the same buckets as queue metrics, to a user who can read the cluster and holds the permission the metric declares. Plugin UIs SHALL be able to draw it with the SDK's metric chart.

#### Scenario: A series without the permission is refused
- **WHEN** a user without `acme-notes:stats` requests the series of `acme-notes:edits`
- **THEN** the request is refused, as any cluster read the user may not make is
