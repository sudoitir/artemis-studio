# telemetry-export Specification

## Purpose
Defines how Studio hands its own metrics, traces and logs to an operator's observability stack: OTLP export that is off until switched on, traces that follow a request across the broker and database calls it causes, redaction of everything exported, JSON logs that join their traces, and the dashboards and alert rules that ship with each release.

## Requirements

### Requirement: OTLP export is configurable and off by default

The system SHALL export metrics, traces and logs over OTLP only when export is switched on. Export
SHALL be configured with the standard OpenTelemetry SDK environment variables for endpoint,
exporters, headers and sampling. By default, the system SHALL send nothing.

#### Scenario: Default

- **WHEN** export is not switched on
- **THEN** no telemetry leaves Studio

#### Scenario: Configured

- **WHEN** an endpoint is configured and the three exporters are switched on
- **THEN** metrics, traces and logs all arrive at that endpoint

### Requirement: A trace follows a request across boundaries

A trace SHALL connect three things: an incoming HTTP request or a background job run, the
management and message-transport calls it causes, and its database queries. Each of those calls
SHALL be its own span, naming the node it went to.

#### Scenario: Slow request

- **WHEN** a request is slow
- **THEN** its trace shows the time spent in the HTTP handler, each management or transport call, and each database query

### Requirement: Exported telemetry carries no secrets or message content

Exported logs, spans and attributes SHALL follow the same redaction rules as Studio's console
logs. They SHALL never include message payloads, message headers, management call arguments or
database parameter values.

#### Scenario: Sensitive attribute

- **WHEN** a span would include a credential or payload
- **THEN** it is omitted or redacted

#### Scenario: Credential in an exported log

- **WHEN** a log line or exception message containing a credential is exported over OTLP
- **THEN** the exported record carries it redacted

### Requirement: Dashboards and alert rules ship with each release

The repository and each release SHALL include Grafana dashboards and Prometheus alert rules. They
SHALL work against Studio's metrics, and CI SHALL check them against the metric names Studio
actually exposes and check that the rules are valid.

#### Scenario: Renamed metric

- **WHEN** a metric used by a dashboard or rule is renamed
- **THEN** CI fails

#### Scenario: Release asset

- **WHEN** a release is published
- **THEN** it carries an archive of the dashboards and rules

### Requirement: Logs can be written as structured JSON with trace identifiers

The system SHALL be able to write its logs as one JSON object per line, chosen by configuration.
Each line SHALL carry the trace and span identifiers of the request that produced it.

#### Scenario: A log line joins its trace

- **WHEN** JSON logging is on and a traced request logs an error
- **THEN** the line is valid JSON and carries the trace identifier that the exported trace has

#### Scenario: Redaction holds in JSON

- **WHEN** a log line would carry a credential
- **THEN** the JSON line is redacted like the plain one
