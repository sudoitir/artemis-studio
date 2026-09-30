## Context

Studio is one Spring Boot 4.1 module. Its only observability dependencies are actuator and
`micrometer-registry-prometheus`; there is no tracing. Redaction (ADR-0133) sits at the logging
choke points: `SecretRedactor` holds the rules, `RedactingLogging` swaps Logback's message and
throwable converters, and `RedactingJsonMembers` redacts Boot's structured JSON output. Custom
meters are `studio.job` (`JobStatuses`), `studio.broker.requests|permit.wait|permit.timeouts`
(`NodeCallLimiter`, tagged `node` with the raw Jolokia URL), `artemisstudio.rr.latency` and the
flow sampler meters. Jolokia clients are built from a bare `RestClient.builder()` in
`BrokerClientFactory.forNode()`. The Core clients (`CoreMessageTransport`, `CorePool`,
`CoreRelay`, `CoreEventClient`) and JDBC are not instrumented. `SseHub` counts clients per
cluster only, privately. `JobStatus` already records last start, last end, interval and next
run. `GET /api/v1/system/jobs` exists and has no UI.

## Goals / Non-Goals

**Goals:**
- One switch turns OTLP export on for metrics, traces and logs. Nothing leaves Studio without it.
- Every span and exported log goes through the same redaction as the console log.
- One Settings screen shows Studio's own health, with "unavailable" distinct from zero.
- The dashboards and rules are checked against the real metric names in CI.

**Non-Goals:**
- Tracing inside the broker, and propagating trace context into broker messages. Spans stop at
  Studio's side of the Jolokia and Core calls.
- Per-plugin tracing APIs. Plugin code runs inside Studio's request spans, and that is enough for now.
- Authenticating `/actuator/prometheus`. It is unauthenticated today, which predates this change.

## Decisions

### D1. Boot's OpenTelemetry starter, Micrometer Observation as the single instrumentation API
Add `spring-boot-starter-opentelemetry`. It brings the Micrometer Tracing OTel bridge, the OTLP
span exporter and the OTLP meter registry, and they sit next to the Prometheus registry that
already exists. Logs use `opentelemetry-logback-appender-1.0`, installed from the `OpenTelemetry`
bean (Boot's documented `OpenTelemetryAppender.install`). All of Studio's own instrumentation is
Micrometer `Observation`, so one call site yields both a timer and a span.
*Alternatives:* the OTel Java agent can't ship inside the jar or the image as a dependency, and it
instruments behind our redaction. Raw OTel API spans would give us two instrumentation styles.

### D2. Off by default through Boot properties, one switch and one endpoint
`application.yml` maps `STUDIO_OTLP_ENABLED` (default `false`) onto
`management.otlp.metrics.export.enabled` and `management.tracing.export.enabled`. It maps
`STUDIO_OTLP_ENDPOINT` (default `http://localhost:4318`) onto the three signal URLs
(`/v1/metrics`, `/v1/traces`, `/v1/logs`). The log appender is installed only when the switch is on.
Tracing itself stays on, so trace ids still reach the MDC and the JSON logs when export is off.
Sampling defaults to 1.0 (`management.tracing.sampling.probability`). Studio is a low-traffic
admin tool, and one slow request is exactly what an operator wants to find. Every property stays
overridable with Boot's own names.
*Alternative:* an `EnvironmentPostProcessor` that derives everything from one endpoint property.
That is code for what placeholders already do.

### D3. Where spans come from
- **HTTP server:** Boot's server observation.
- **Jolokia:** `BrokerClientFactory` takes the `ObservationRegistry` and sets it on each
  `RestClient.Builder`. A custom `ClientRequestObservationConvention` names the observation
  `studio.broker.management`. Its low-cardinality tags are `node` (see D5) and `outcome`, and the
  Jolokia operation type (`read`, `exec`, `list`) goes on the span. MBean arguments and bodies are
  never recorded.
- **Core:** Artemis has no OTel instrumentation. A small `CoreObservations` helper wraps the
  operations at the transport choke points (send, browse, receive, relay request, pool borrow) in
  `studio.broker.core` observations. They are tagged `node` and `operation`, and never with
  message content or headers.
- **JDBC:** `net.ttddyy.observation:datasource-micrometer-spring-boot` wraps the Hikari
  `DataSource`, so JPA, `NamedParameterJdbcTemplate` and `JdbcClient` are covered alike. Parameter
  values stay off, which is its default. Query text is kept, because statements carry `?`
  placeholders, not values. The plugin data sources (ADR-0101) are covered only if the
  auto-configuration reaches them, and that is not required.
- Scheduled jobs are wrapped in an observation by `JobStatuses`, so a scrape tick is a root span
  with its Jolokia children.

### D4. Redaction at export, reusing `SecretRedactor`
- An `ObservationFilter` runs every key-value through `SecretRedactor.redact`. It drops any key
  whose name marks it as content (`body`, `payload`, `message.content`). It also redacts the error
  message that becomes the span's exception event.
- A `LogRecordProcessor` redacts the OTel log body, the string attributes and
  `exception.message`/`exception.stacktrace` before the batch exporter. The appender reads the
  formatted message itself, so the Logback converters never touch it.
- The JSON console log is already redacted by `RedactingJsonMembers`.

Tests push a credential through each path into an in-memory exporter and assert that it does not
arrive.

### D5. Metric names and the `node` tag
- The `node` tag becomes the Jolokia URL's `host:port`, on the limiter's meters and on the new
  ones. Today it carries the full URL, and a URL can hold `user:password@`. The fix is breaking,
  and that is allowed.
- `artemisstudio.rr.latency` is renamed `studio.rr.latency`, to match the other meters.
- New meters:
  - `studio.job.lag` gauge per `job`: time since the last completed run minus the interval,
    floored at zero.
  - `studio.broker.management` timer, from D3.
  - `studio.broker.core` timer, from D3.
  - `studio.stream.clients` gauge, the total across `SseHub`.
- Pool use comes from Hikari's own `hikaricp.connections.*`.

### D6. Self-health read and view
`GET /api/v1/system/health` (settings-read) returns:
- jobs: status, lag, degraded;
- nodes: last success, last failure and error, p95 management latency, last rate-limit wait;
- the database pool: active, idle, max, pending;
- stream clients;
- an overall `degraded` flag.

Every figure is nullable. A figure the server can't read is `null`, and the UI renders it as
"unavailable", never as 0. The read is assembled from the `JobStatuses`, `NodeCallHealth`,
`NodeCallLimiter` and `SseHub` beans and the `MeterRegistry`. It adds no new state. In the UI, a
"Studio health" section in Settings polls it every 5 s. It shows tables with a degraded badge per
row, plus the loading, empty and error states. The actuator `studio` health group is unchanged.

### D7. Dashboards and alert rules as checked files and a release asset
- The files are `deploy/observability/grafana/studio-overview.json` and
  `deploy/observability/prometheus/studio-alerts.yml`.
- The alerts cover: a job degraded or lagging, a broker node failing management calls, management
  p95 latency, rate-limit timeouts, pool saturation and stream client count.
- Both files are written against the Prometheus exposition names that `/actuator/prometheus`
  serves. An OTLP-to-Prometheus pipeline with default name translation yields the same names.
- `ObservabilityAssetsIT` reads both files and extracts every metric name. It exercises the app,
  scrapes `/actuator/prometheus` and fails on any name that is absent.
- CI runs `promtool check rules` in the `prom/prometheus` image.
- The release job tars `deploy/observability/` into `artemis-studio-observability-<version>.tar.gz`
  and attaches it with a checksum next to the jar.

### D8. Structured JSON logs
Boot's structured logging is already redacted. Setting `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`
switches the console to one ECS JSON object per line. The Micrometer Tracing MDC puts `traceId`
and `spanId` on the line. The only new pieces are the documentation and a test proving that the
line's trace id equals the exported span's.

## Risks / Trade-offs

- [JDBC spans on every query make scrape traces large] → the scrape tiers batch their writes, and
  sampling is configurable. The docs name the probability property.
- [datasource-micrometer lags a Boot major] → check its Boot 4 compatibility with ctx7 before
  adding it. If no compatible release exists, fall back to `datasource-micrometer` core, wired by
  a `BeanPostProcessor` on the `DataSource`.
- [The OTel appender reads the raw message] → D4's `LogRecordProcessor`, covered by a test.
- [The renamed meters and `node` tag break existing Prometheus queries] → a breaking commit with
  a `BREAKING CHANGE:` note, and the shipped dashboards use the new names.
- [Cardinality: a `node` tag per host:port] → bounded by registered nodes, as before.

## Migration Plan

None. The export is opt-in. Existing scrapes see the renamed meters, which the release note lists.
