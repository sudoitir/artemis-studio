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
| [0018](0018-sse-hub.md) | SSE hub is `SseEmitter` on Spring MVC, carrying poll-derived change signals (annotates 0003) |
| [0019](0019-openapi-generated-frontend-types.md) | Frontend API types generated from the backend's OpenAPI document |
| [0020](0020-grid-columns-as-css-grid-tracks.md) | The virtualized data grid lays out on shared CSS grid tracks, not `<table>` |
