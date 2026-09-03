## Why

Phase 1 can tell an operator which node is live and whether replication is
healthy, but nothing in the product shows what is *on* the cluster: no queues,
no addresses, no consumers, and no live updates. The scheduler that feeds the
topology view holds a database transaction open across every node's HTTP call
and runs every cluster serially — it is not safe to point at a broker under
load. Phase 2 builds the cross-node resource views the MVP bar promises ("every
queue across every node in one table") on top of a broker-friendly tiered
scrape, and gives the browser a live channel so those views move on their own.

## What Changes

- **New tiered scrape scheduler** — tier A (HA + topology, 5s), tier B (hot
  queue page, 15s), tier C (full queue sweep, 5m), each one batched Jolokia POST
  per manageable node. Network I/O happens outside any database transaction. A
  per-node token bucket caps management calls/sec so Studio can never overload a
  broker.
- **BREAKING (internal, pre-release):** `scheduler/HaRefreshTask` is deleted
  whole and replaced by the new scheduler. The split-brain monotonic cycle
  counter and the one-cycle corroboration ratchet move out of the retired task
  into scheduler-owned, **per-cluster** state (ADR-0012 deferred "where this
  state lives" to Phase 2). `HaStateEvaluator` loses its mutable map — read
  endpoints stop advancing the split-brain ratchet.
- **`queue_snapshot` becomes a live cache** — a JDBC batched
  `INSERT … ON CONFLICT` upsert per sweep, stale rows reaped per node.
  `metric_sample` gets tier-B/C writes plus a nightly 7-day retention reaper
  (ADR-0006's stated default; daily partitioning stays Phase 6).
- **New cross-node read API** — `GET /api/v1/clusters/{id}/queues` (aggregated
  from `queue_snapshot`, one row per queue across all logical nodes) and
  `.../{addresses,consumers,sessions,connections,producers}` (live-through: one
  batched POST per live node, merged and tagged by node). All six broker list
  operations share one signature and are served through one paged-list helper.
- **New SSE hub** — `GET /api/v1/stream?clusterId=&topics=` over `SseEmitter`
  (ADR-0010), multiplexing `topology` / `health` / `queues` invalidation events
  derived from the scrape path only. Two `EventSource` failures degrade the
  client to TanStack Query polling (ADR-0003).
- **Real React shell** — TanStack Router wired over the Phase 1 screen
  (navigable state moves to the URL), the identity-axis topology graph rebuilt
  in React Flow (`PairSpine` deleted, its grammar inherited exactly), a
  virtualized TanStack Table queue grid, and a ⌘K command palette.
- **Frontend types generated from OpenAPI** — `springdoc` on the backend,
  `openapi-typescript` in the web build; the hand-written DTO mirrors in
  `web/src/api/client.ts` are deleted.
- **New settings area** — a `studio_setting` table and `GET/PUT /api/v1/settings`
  for scrape cadences, the limiter cap, and metric retention; consolidation of
  the Phase 1 cluster register / rediscover / remove flows; and broker-credential
  rotation (`PUT /api/v1/clusters/{id}/credentials`, re-encrypted via
  `SecretVault`, audited in transaction).
- **New schema changeset** `009-studio-settings.sql`. Released changesets
  001–008 untouched. `queue_snapshot` / `metric_sample` (changeset 005) gain
  entities, writers, and readers but no schema change.
- **Five new ADRs** — 0015 (tiered scheduler & cycle-counter ownership),
  0016 (`queue_snapshot` bulk upsert — scoped departure from ADR-0011),
  0017 (cross-node aggregation contract), 0018 (SSE hub over `SseEmitter` —
  cites ADR-0010, annotates ADR-0003's stale `Flux` consequence), 0019
  (OpenAPI-generated frontend types).
- **New dependencies** — backend: `springdoc-openapi-starter-webmvc-ui:3.1.0`
  (built against Spring Boot 4.1.0). Frontend: `@tanstack/react-virtual`;
  dev-only `openapi-typescript`; `@tanstack/router-plugin` wired into Vite.

## Capabilities

### New Capabilities

- `scrape-scheduling`: the tiered A/B/C scrape scheduler, the per-node
  management-call rate limiter, the queue-snapshot upsert and stale-row reap,
  metric-sample writes and retention, and scheduler ownership of the split-brain
  cycle counter and corroboration ratchet.
- `cross-node-resource-views`: the aggregation contract and read API for
  queues, addresses, consumers, sessions, connections, and producers across a
  cluster's logical nodes — pair de-duplication by shared `NodeID`, the
  per-node cell model, and how unmanageable or stale nodes are represented.
- `realtime-stream`: the SSE hub — `GET /api/v1/stream`, the topic model,
  poll-derived invalidation events, the heartbeat and proxy-buffering contract,
  and the two-failure fallback to polling.
- `studio-settings`: DB-backed operational settings (scrape cadences, limiter
  cap, metric retention) with seed-from-config precedence, and broker-credential
  rotation.

### Modified Capabilities

- `cluster-topology`: split-brain detection state (cycle counter + one-cycle
  ratchet) moves from the retired `HaRefreshTask` to scheduler-owned per-cluster
  state; the topology and health views are served from persisted scrape results
  rather than a live probe on every read, and reading them no longer advances
  the split-brain ratchet.

## Impact

- **Code**: new `scheduler/` (rewritten), `sse/`, `service/` (paged-list,
  aggregator, settings), `persist/` (queue-snapshot entity + JDBC upsert,
  metric-sample writer/reaper, studio-setting), `web/` (resource, stream,
  settings controllers + DTOs), `mapper/` additions;
  `scheduler/HaRefreshTask` deleted; `domain/topology/HaStateEvaluator` loses
  its mutable ratchet. Frontend: `web/src/routes/**`, `src/topology/`,
  `src/queues/`, `src/resources/`, `src/palette/`, `src/settings/`, generated
  `src/api/schema.d.ts`, rewritten `src/api/client.ts`, new `src/api/stream.ts`;
  `src/clusters/PairSpine.*` deleted; `src/theme.css` gains grid/graph/palette
  tokens and the light-mode block.
- **APIs**: adds `GET /api/v1/clusters/{id}/{queues,addresses,consumers,
  sessions,connections,producers}`, `GET /api/v1/stream`,
  `GET|PUT /api/v1/settings`, `PUT /api/v1/clusters/{id}/credentials`, and
  `/v3/api-docs` (+ Swagger UI).
- **Dependencies**: backend `+springdoc-openapi-starter-webmvc-ui:3.1.0`;
  frontend `+@tanstack/react-virtual`, dev `+openapi-typescript`, Vite router
  plugin enabled.
- **Config**: `application.yml` gains `spring.task.scheduling` pool config and
  `artemis-studio.metric.retention-days: 7`; runtime values for cadences, the
  limiter cap, and retention are overridable from `studio_setting`.
- **Schema**: one additive changeset `009-studio-settings.sql`.
- **Docs**: ADRs 0015–0019 added; ADR-0003 annotated; `docs/architecture.md`
  tier table and SSE mention reconciled, cross-node aggregation section added;
  `docs/broker-management-notes.md` gains a Phase 2 surface-checks section;
  `README.md` Phase 2 rows ticked and the stale status line fixed;
  `openspec/project.md` current-phase line updated.
