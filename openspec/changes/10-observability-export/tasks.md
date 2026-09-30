## 1. Telemetry export and tracing (slice A)

- [x] 1.1 Add `spring-boot-starter-opentelemetry`, `opentelemetry-logback-appender-1.0` and `datasource-micrometer-spring-boot`, checking Boot 4.1 compatibility with ctx7
- [x] 1.2 Default the three OTLP export properties to off in `application.yml` and rely on the standard `OTEL_*` variables that Boot maps (endpoint, exporters, headers, sampler), with sampling at 1.0 (D2)
- [x] 1.3 Install the OTel Logback appender only when export is on
- [x] 1.4 Give Jolokia clients the `ObservationRegistry` and a `studio.broker.management` convention (tags `node`=host:port, `outcome`) (D3)
- [x] 1.5 Wrap the Core transport choke points in `studio.broker.core` observations: send, browse, receive, relay and pool borrow (D3)
- [x] 1.6 Wrap each job run in an observation in `JobStatuses`
- [x] 1.7 Redact at export: an `ObservationFilter` for key-values and error messages, and a `LogRecordProcessor` for OTel log bodies, attributes and exceptions (D4)
- [x] 1.8 Tests:
  - default config sends nothing;
  - with an endpoint and the three exporters on, all three signals reach a mock OTLP endpoint;
  - one HTTP request produces a trace with Jolokia, Core and JDBC child spans;
  - a credential never reaches an exported span or log;
  - an ECS JSON log line's trace id equals the exported span's

## 2. Self-health (slice B)

- [ ] 2.1 `NodeCallLimiter` tags `node` with host:port. Rename `artemisstudio.rr.latency` to `studio.rr.latency` (D5)
- [ ] 2.2 Add the `studio.job.lag` gauge per job and the `studio.stream.clients` gauge
- [ ] 2.3 Add `GET /api/v1/system/health` (settings-read) with nullable figures and an overall `degraded` flag (D6). Regenerate the OpenAPI snapshot and `schema.d.ts`
- [ ] 2.4 Add a "Studio health" Settings section: polling, a degraded badge per row, "unavailable" for null, and empty and error states
- [ ] 2.5 Tests: controller permission, null-as-unavailable, lag computation, and the web component states

## 3. Dashboards, rules, release, docs (slice C)

- [ ] 3.1 Add `deploy/observability/grafana/studio-overview.json` and `deploy/observability/prometheus/studio-alerts.yml` (D7)
- [ ] 3.2 Add `ObservabilityAssetsIntegrationTest`: every metric name in both files is present on `/actuator/prometheus`
- [ ] 3.3 In CI, run `promtool check rules`. In the release job, attach `artemis-studio-observability-<version>.tar.gz` and its checksum
- [ ] 3.4 Add `site/src/guide/observability.md` (the OTEL_* variables, JSON logs, redaction, dashboards, sampling) and a sidebar entry
- [x] 3.5 Add ADR-0147 "OpenTelemetry export through Micrometer Observation, redacted at export"

## 4. Finish

- [ ] 4.1 `just verify` green
- [ ] 4.2 Studio screenshots of the health view: light and dark, healthy, degraded, and error
- [ ] 4.3 PR merged on green CI and SonarCloud; `/opsx:archive`
