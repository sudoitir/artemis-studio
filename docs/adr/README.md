# Architecture Decision Records

Binding. Design, code, dependencies and APIs comply with every accepted ADR here.
Before proposing or implementing, skim this directory for decisions touching your
scope. A new significant decision — adding a technology, changing a pattern,
departing from a convention — needs a new sequential ADR before or with the
change. Never edit an accepted ADR's decision; supersede it.

Format: `NNNN-kebab-title.md`, English, Nygard style (`000-template.md`).

| ADR | Decision |
|---|---|
| [0001](0001-project-name-and-trademark-risk.md) | Name "Artemis Studio"; trademark risk accepted with a cheap rename path |
| [0002](0002-broker-transport-and-capability-model.md) | Jolokia-first transport, Core client second, capability-gated features |
| [0003](0003-realtime-via-sse.md) | Real-time updates over SSE |
| [0004](0004-topology-seed-and-autodiscovery.md) | Topology by seed node + auto-discovery |
| [0005](0005-frontend-stack-mantine-over-shadcn-and-mui.md) | React 19 + Vite + Mantine 9 |
| [0006](0006-metrics-in-postgres.md) | Own the metrics timeseries in PostgreSQL |
| [0007](0007-packaging-single-image-compose-first.md) | One container image, Docker Compose first |
| [0008](0008-schema-migrations-liquibase.md) | Liquibase migrations; schema physical tuning |
| [0009](0009-secret-vaulting-and-broker-tls.md) | Secret vaulting with JDK AES-GCM; broker TLS via SSL bundles |
| [0010](0010-jolokia-over-restclient.md) | Jolokia over a blocking `RestClient`; `spring-boot-starter-webflux` removed |
| [0011](0011-jdbcclient-over-spring-data-jdbc.md) | Persistence via JPA (Hibernate) mapped to the Liquibase-owned schema |
| [0012](0012-corroborated-split-brain.md) | Split-brain detection requires corroborated evidence (amends 0002) |
| [0013](0013-seed-is-a-list.md) | A cluster is registered from a list of seed URLs (amends 0004) |
| [0014](0014-lombok-and-mapstruct.md) | Lombok for boilerplate, MapStruct for layer-to-layer mapping |
| [0015](0015-tiered-scrape-scheduler.md) | Tiered scrape scheduler; refresh-cycle counter scheduler-owned per cluster (retires `HaRefreshTask`) |
| [0016](0016-queue-snapshot-bulk-upsert.md) | `queue_snapshot` written by JDBC batch upsert, not JPA (scoped exception to 0011) |
| [0017](0017-cross-node-aggregation.md) | Cross-node aggregation — one logical node per NodeID, scrape the live endpoint only |
| [0018](0018-sse-hub.md) | SSE hub is `SseEmitter` on Spring MVC, carrying poll-derived change signals (annotates 0003; extended by 0027) |
| [0019](0019-openapi-generated-frontend-types.md) | Frontend API types generated from the backend's OpenAPI document |
| [0020](0020-grid-columns-as-css-grid-tracks.md) | The virtualized data grid lays out on shared CSS grid tracks, not `<table>` |
| [0021](0021-message-operations-jolokia-only.md) | ~~Phase 3 message operations are Jolokia-only~~ — superseded by 0029 |
| [0022](0022-dry-run-estimate-and-server-enforced-bulk-cap.md) | Dry-run is a broker-side estimate; the bulk safety cap is server-enforced |
| [0023](0023-audit-actor-before-authentication.md) | Audit actor resolution before authentication exists (`anonymous` + request context) |
| [0024](0024-frontend-dom-test-harness.md) | Frontend DOM test harness is Vitest + Testing Library + MSW |
| [0025](0025-live-scrape-cadence-scheduling-configurer.md) | Scrape cadence applies without a restart, via `SchedulingConfigurer` |
| [0026](0026-core-client-connection-model.md) | Core client — one subscription per live node, poll loop, Studio-driven reconnect, cached capability verdict (extended by 0031) |
| [0027](0027-sse-events-topic-carries-data.md) | The SSE `events` topic carries data, with `Last-Event-ID` replay and coalesced derived signals (extends 0018) |
| [0028](0028-broker-event-persistence.md) | `broker_event` — buffered batch insert, bounded queue with a visible drop counter, `seq` PK |
| [0029](0029-message-transport-two-implementations.md) | `MessageTransport` — Core for read/write fidelity, Jolokia for mutations and deep pages (supersedes 0021) |
| [0030](0030-request-reply-correlation-strategy.md) | Request-reply correlation is notification-anchored and browse-sampled, with a disclosed coverage ceiling |
| [0031](0031-pooled-core-connections.md) | Pooled Core connections via `pooled-jms`, superseding connect-per-call |
| [0032](0032-rr-latency-micrometer-percentiles.md) | Request-reply latency via Micrometer time-windowed percentiles, no persisted history in Phase 5 |
| [0033](0033-metric-read-model.md) | Metric read model — `date_bin` bucketing, gauge vs. counter-rate, server-clamped step/range |
| [0034](0034-collapsible-sidebar.md) | Two-section collapsible sidebar (cluster switcher + per-cluster view nav), replacing the horizontal view strip |
| [0035](0035-alert-rule-model-and-evaluation-timing.md) | Alert rules are a discriminated union (metric threshold / cluster state); evaluation rides the scrape tiers, not an independent timer |
| [0036](0036-notification-delivery-queue-and-channel-signing.md) | Notification delivery is a durable Postgres queue, batched per rule per tick, with Standard Webhooks signing |
