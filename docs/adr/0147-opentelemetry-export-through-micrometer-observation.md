# ADR-0147: OpenTelemetry export through Micrometer Observation, redacted at export

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/10-observability-export`

## Context

Studio published Prometheus metrics through Actuator and nothing more. Operators who run
OpenTelemetry could not collect its metrics, traces or logs. Nobody could follow a slow request
through the Jolokia or Core call it made down to Postgres. Credentials are redacted at the logging
choke points (ADR-0133), but any new path that carries log text or attributes out of the process
would bypass those converters. The OpenTelemetry Logback appender is one such path: it reads the
formatted message itself.

## Decision

- **Boot's `spring-boot-starter-opentelemetry` is the export stack**, next to the existing
  Prometheus registry. Logs go through the OpenTelemetry Logback appender, installed from the
  `OpenTelemetry` bean.
- **Micrometer `Observation` is Studio's only instrumentation API.** The HTTP server, the Jolokia
  `RestClient`s, the Core transport choke points, scheduled job runs and JDBC
  (`datasource-micrometer`) each produce observations. One call site yields both a metric and a span.
- **Export is off by default and uses the standard OTEL_* variables:**
  - Boot 4.1 maps them onto its own properties. `OTEL_EXPORTER_OTLP_ENDPOINT` names the collector, and
    `OTEL_TRACES_EXPORTER`, `OTEL_METRICS_EXPORTER` and `OTEL_LOGS_EXPORTER` set to `otlp` turn the three
    signals on. Headers, sampler and resource attributes come along.
  - Tracing runs even when export is off, so JSON logs still carry trace ids.
  - Sampling defaults to 1.0.
- **Redaction happens again at export, with the same `SecretRedactor`:**
  - An `ObservationFilter` covers span and metric key-values and error messages.
  - Wrappers around every span and log exporter cover OpenTelemetry log bodies, span and log attributes, and exceptions.
  - Payloads, message headers, management arguments and JDBC parameter values are never recorded.
- **A broker node is identified in telemetry by `host:port`, never by its URL**, because the URL
  can carry `user:password@`.

## Consequences

- Operators get all three signals, and one trace per request or job tick across every hop Studio
  controls. Spans stop at the broker: no context goes into broker messages.
- Every new exporter or attribute source must go through the same redaction. The export tests
  push a credential through each path, and they fail if a new path skips it.
- JDBC spans make scrape traces large. Sampling is the operator's lever.
- Renaming the `node` tag and `artemisstudio.rr.latency` breaks existing queries once. The
  shipped dashboards use the new names.

## Alternatives considered

- **The OpenTelemetry Java agent.** It can't ship as a dependency, and it instruments beneath the
  redaction choke points.
- **The raw OpenTelemetry API for Studio's own spans.** That would give two instrumentation styles
  and metrics kept separately from spans.
- **Custom `STUDIO_OTLP_*` variables.** They are non-standard, and Boot 4.1 already maps `OTEL_*`.
