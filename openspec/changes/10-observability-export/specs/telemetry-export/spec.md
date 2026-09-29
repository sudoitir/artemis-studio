## ADDED Requirements

### Requirement: OTLP export is configurable and off by default
Studio SHALL export metrics, traces and logs over OTLP only when configured, and SHALL send nothing by default.

#### Scenario: Default
- **WHEN** no endpoint is configured
- **THEN** no telemetry leaves Studio

#### Scenario: Configured
- **WHEN** an endpoint is configured
- **THEN** all three signals arrive there

### Requirement: A trace follows a request across boundaries
A trace SHALL connect an incoming HTTP request with the management and core calls it causes and with its database queries.

#### Scenario: Slow request
- **WHEN** a request is slow
- **THEN** its trace shows the time spent in each hop

### Requirement: Exported telemetry carries no secrets or message content
Exported logs, spans and attributes SHALL follow the redaction rules and SHALL never include message payloads.

#### Scenario: Sensitive attribute
- **WHEN** a span would include a credential or payload
- **THEN** it is omitted or redacted

### Requirement: Dashboards and alert rules ship with each release
The repository and each release SHALL include Grafana dashboards and Prometheus alert rules that work against Studio's metrics, checked in CI against the metric names.

#### Scenario: Renamed metric
- **WHEN** a metric used by a dashboard is renamed
- **THEN** CI fails
