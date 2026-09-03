## Context

See `proposal.md` — Why. The full brainstormed slice plan with pseudocode lives
at `/home/sudoit/.claude/plans/phase-2-cross-node-toasty-possum.md` and is the
working reference for `/opsx:apply`.

Current state after Phase 1:

- `JolokiaBrokerClient.batch(List<JolokiaRequest>)` and
  `JolokiaResponse.valueParsed(mapper)` (double-decode) exist and are tested, but
  **no production code calls `batch()`** — every path uses `single()` and rebuilds
  the client, discarding the cached broker object name and paying an extra
  `search` POST per tick.
- `scheduler/HaRefreshTask` is one `@Scheduled` method, serial across all
  clusters, the whole tick inside one `@Transactional`, HTTP calls inside that
  transaction, a single global cycle counter. Its Javadoc says Phase 2 deletes it.
- `queue_snapshot` and `metric_sample` (changeset 005) exist with no entity,
  repository, or writes. `queue_snapshot` PK is `(node_id, queue_name)` —
  no `address` column in the key.
- `HaStateEvaluator` holds a mutable `Map` for the split-brain ratchet that is
  advanced from read endpoints as well as the scheduler.
- Frontend is routerless; `PairSpine` renders the identity axis; DTO types in
  `web/src/api/client.ts` are hand-written mirrors of the Java records with no
  drift guard; TanStack Router / Table and `@xyflow/react` are installed but
  unused.

Binding constraints: CLAUDE.md non-negotiables #1–#10; ADR-0002/0003/0005/0006/
0010/0011/0012/0013; the global engineering principles (no backward-compat
layers, simplest implementation that meets the requirement, prefer established
libraries, decisions for the long term). `ctx7` for every library/API fact.
Phase 0's `docs/broker-management-notes.md` is the verified broker surface.

## Goals / Non-Goals

**Goals:**

- A broker-safe tiered scrape: one batched POST per node per tick, network I/O
  outside DB transactions, a per-node call ceiling, and a single owner for the
  refresh-cycle counter.
- `queue_snapshot` as a live cross-node cache with a set-based upsert and a
  stale-row reap; `metric_sample` written and bounded.
- Six cross-node read views over one shared paged-list mechanism; queues from the
  cache, the other five live-through.
- One SSE endpoint carrying poll-derived change signals, with an automatic
  polling fallback.
- A real React shell: URL-owned navigable state, the identity-axis grammar in
  React Flow, a virtualized queue grid, a ⌘K palette — one visual language.
- Frontend types generated from the backend's OpenAPI document.
- A settings surface for the operational knobs plus credential rotation.

**Non-Goals (design-level boundaries):**

- No push/notification-sourced events — the Core client and true realtime are
  Phase 4. Every Phase 2 SSE event is derived from the scrape path.
- No message operations (browse/send/move/purge) — Phase 3.
- No charts or rollup tables over `metric_sample` — Phase 6. Phase 2 only writes
  it and trims it; daily partitioning stays Phase 6.
- No multi-instance scheduler leadership — the per-cluster advisory-lock seam is
  left in place, not built.
- No auth, RBAC, or OIDC — Phase 8. The unauthenticated-API startup warning is
  unchanged.
- No responsive/mobile layout — the console is desktop-only (min-width ~1100px).

## Decisions

### D1 — Retire `HaRefreshTask`; one tiered scheduler on a pool (ADR-0015)

A single `scheduler/ScrapeScheduler` runs three `@Scheduled` methods on a pooled
`ThreadPoolTaskScheduler` (virtual threads; `spring.task.scheduling` config).
Tier A (5s) = HA attrs + `listNetworkTopology()` + broker counters in one
`batch()` POST. Tier B (15s) = one `listQueues` page of the hot queues. Tier C
(5m) = one `listQueues` page per tick advancing a per-node sweep cursor until the
full set is covered, then idle.

Per node: acquire the limiter → POST → parse → hand a plain result object to a
short `@Transactional` persist step. **No HTTP inside a transaction.** One node's
failure is caught, recorded on `last_error`, and never propagated.

*Alternatives:* keep one `@Scheduled` and add tiers as branches — rejected, the
serial single-transaction shape is the problem, not the method count. Spring
Batch / Quartz — rejected, far heavier than three fixed-delay methods need.

### D2 — Split-brain cycle state moves into the scheduler, per cluster (ADR-0015, amends ADR-0012 area)

ADR-0012 explicitly deferred "where this state lives" to Phase 2. A
`scheduler/ScrapeCycle` component owns a per-cluster `AtomicLong` and the
per-`(cluster,node)` `firstSuspectedCycle` map, advanced only by tier A.
`HaStateEvaluator` keeps its pure derivations (`deriveState`, `deriveHaRole`,
`toLogicalNodes`, `toHealth`) and **loses the mutable map**. `GET /topology` and
`GET /health` read the last persisted `broker_node` state and the last computed
split-brain status — they no longer probe the broker or advance the ratchet
(this is the `cluster-topology` MODIFIED delta). The ~2-cycle / ~10s detection
ceiling is preserved for whatever `tier-a-interval` is configured.

### D3 — `queue_snapshot` upsert is JDBC `INSERT … ON CONFLICT`, not JPA (ADR-0016)

`queue_snapshot` is a disposable cache with no stable row identity, rewritten
thousands of rows per sweep. ADR-0011's JPA dirty-checking mandate targets the
*estate* tables (child-UUID churn, audit FK stability) and does not fit here. Use
`NamedParameterJdbcTemplate.batchUpdate` with
`INSERT … ON CONFLICT (node_id, queue_name) DO UPDATE`, and reap stale rows per
node with `DELETE … WHERE node_id = :n AND ts < :sweepStart`. `JdbcTemplate` is
already on the classpath via `spring-boot-starter-data-jpa` — no new dependency.
A read-only `QueueSnapshotEntity` + `JpaRepository` serves the read API; writes
never go through JPA. The estate tables stay pure JPA per ADR-0011.

*Alternatives:* batch JPA `saveAll` with a `@SQLInsert` — still round-trips the
persistence context per row and fights the `IDENTITY`-free composite key. jOOQ /
MyBatis — new dependency + codegen for one statement. `MERGE` — non-standard vs
`ON CONFLICT` which is the canonical Postgres upsert.

### D4 — Cross-node aggregation contract (ADR-0017)

A pair shares one `NodeID` ⇒ one logical node; scrape the **live** endpoint only,
so queues are never double-counted. A queue view row is keyed
`(clusterId, address, queueName, routingType)` with a `perNode` cell list plus
rolled-up totals and a `nodesPresent / nodesTotal` count. Known-but-unmanageable
nodes (ADR-0013) appear in the node dimension as present-without-data, never
zeroed, never omitted. An unreachable live endpoint keeps its last cached rows,
marked `stale` with `lastSeenAt` — the scheduler does not reap a node's rows
because one sweep failed.

### D5 — Only queues are cached; the other five views are live-through

Queues need cross-node aggregation, history, and SSE deltas, so they are scraped
into `queue_snapshot`. Addresses / consumers / sessions / connections / producers
are volatile and read-mostly-once; a `service/PagedListService` fans out one
batched POST per serving node on demand, tags each row with its logical node,
merges, and paginates in memory. All six broker list operations share the
signature `(String options, int page, int pageSize)` → double-encoded
`{"data":[…],"count":N}` (verified for three ops in Phase 0, the rest in
Slice 0), so one `broker/BrokerListOps` helper covers them all. No new snapshot
tables, no new upsert paths, and it makes concrete why Phase 4's push events
matter for exactly these resources.

*Alternative:* cache all six — five more tables, five more upsert/reap paths,
caching data that is stale the instant it lands. Rejected.

### D6 — SSE hub on `SseEmitter`, poll-derived signals only (ADR-0018)

`GET /api/v1/stream?clusterId=&topics=` returns an `SseEmitter` on Spring MVC
(ADR-0010 — no WebFlux; this cites 0010 and annotates ADR-0003's stale
"one `Flux<ServerSentEvent>` per subscriber" consequence with a backlink,
per the "annotate, don't edit" rule). An in-memory `sse/SseHub` keyed by
`clusterId` holds subscribers with their topic sets. After a tier tick the
scheduler diffs the new persisted state against the previous (topology node
set + roles; health enum; changed `queue_snapshot` PKs) and calls
`hub.publish(clusterId, topic)` **only on a real change** — the event payload is
a minimal invalidation signal (`{topic, clusterId, ts}`), not the data; the
client refetches the matching TanStack Query key. A 20s heartbeat comment keeps
idle streams open; `X-Accel-Buffering: no` plus a deployment note. Client: two
consecutive `EventSource` failures ⇒ stop reconnecting and rely on the existing
5s `refetchInterval` (so "fallback" is literally "stop streaming").

### D7 — Frontend types generated from OpenAPI (ADR-0019)

`springdoc-openapi-starter-webmvc-ui:3.1.0` (verified on Maven Central, built
against Spring Boot 4.1.0) emits `/v3/api-docs`; `openapi-typescript` generates
`web/src/api/schema.d.ts` as a build step; the hand-written DTO interfaces in
`api/client.ts` are deleted and the fetch wrappers consume the generated
`paths` / `components["schemas"]`. Contract drift becomes a `verify-web`
failure. *Fallback (recorded in the ADR):* if springdoc is rough on Boot 4.1,
keep hand-written types plus a thin runtime boundary check on the new endpoints
and file generation as a fast-follow.

### D8 — React shell: file-based routing, URL owns navigable state

**Implementation note (Phase 2):** shipped as **code-based** routing
(`createRootRoute` / `createRoute` / `createRouter` in `src/router.tsx`) rather
than file-based with `@tanstack/router-plugin` + a generated `routeTree.gen.ts`.
Same route shape and typed search schema; the plugin's build-time codegen was a
step that could not be validated in the implementation environment, and
code-based routing is a first-class TanStack Router mode. Switching to file-based
later is mechanical. The `ClusterDetailPanel` / `ClusterRail` Phase 1 components
were superseded (not just edited) — their jobs split into `app/ClusterLayout`
(header + health banner + view strip + SSE mount) and `app/ClusterRailNav`.

`@tanstack/router-plugin` in Vite; file-based `src/routes/`. Routes:
`/clusters/$clusterId` (layout: rail + the Phase 1 detail/ledger header) with
children `topology` (default), `queues`, `addresses`, `consumers`, `sessions`,
`connections`, `producers`, `settings`. Selected cluster and each grid's
`q`/`sort`/`page` live in the URL and its typed search schema (non-negotiable
#9); per-route `errorComponent` isolates a panel crash. Provider order:
`MantineProvider` → `QueryClientProvider` → `RouterProvider`. Desktop-only —
`AppShell` loses the mobile `collapsed`/`breakpoint`/`Burger`.

### D9 — Topology graph inherits the identity-axis grammar; `PairSpine` deleted

React Flow becomes the single topology renderer. Custom node components encode
the exact `PairSpine` grammar: `y` = HA role (live above the axis, backup
below), edge `strokeDasharray` = replication behind, both nodes above the axis +
`role="alert"` = split-brain critical, amber axis = suspected, dashed/transparent
node = unmanaged, a status **word** on every node (colour never the sole signal),
retained screen-reader summary sentence, one 240ms cross-axis transition with
`prefers-reduced-motion` honoured. Layout is a pure function of the grammar —
no dagre/elk. `PairSpine.tsx` + `.module.css` are deleted (no compat layer —
global rule); the grammar doc-comment moves into `TopologyGraph.tsx`.

### D10 — Queue grid: TanStack Table v9 headless + `@tanstack/react-virtual`

ADR-0005 fixed TanStack Table, rendered through Mantine `Table`, never
`mantine-react-table`. The installed version is **v9** (`table-core@9.2.4`,
GA), whose API differs from v8 — `useTable` + `tableFeatures({ rowSortingFeature,
sortedRowModel: createSortedRowModel(), … })` + `createColumnHelper<typeof
features, Row>()`; this is re-checked via `ctx7` at implementation time.
`@tanstack/react-virtual` (the sanctioned companion) virtualizes rows past ~50.
Server-side page/sort/filter (`manualSorting`, `manualFiltering`) bound to the
URL search params. Tabular figures on numeric columns, `aria-sort` on sortable
headers, sticky header, a teaching empty state, skeleton (not spinner) past 1s,
a `nodesPresent/nodesTotal` column. Slice 6 reuses the internals as a generic
`ResourceGrid` for the other five views.

### D11 — Settings: DB-backed operational config + credential rotation

New `studio_setting` table (changeset `009`, key `TEXT` PK, `value` `JSONB`,
`updated_at` `TIMESTAMPTZ` first for alignment — non-negotiable #7). A
`SettingsService` reads stored overrides and falls back to
`ArtemisStudioProperties`; the scheduler's `@Scheduled` intervals and the
limiter's ceiling read through it (SpEL against the bean), the retention reaper
reads `metric.retention-days` (default 7). `GET|PUT /api/v1/settings`. Credential
rotation: `PUT /api/v1/clusters/{id}/credentials` re-encrypts via `SecretVault`
(AAD unchanged), audited in-transaction, no secret in any response; the UI
requires typing the cluster name (non-negotiable #2). The Phase 1 register /
rediscover / remove flows are consolidated onto the settings route. RBAC, OIDC,
users/roles, and the audit-log viewer stay Phase 8 / Phase 3.

### D12 — A verification spike first (Slice 0)

`listSessions` / `listConnections` / `listProducers` shapes, the
`GREATER_THAN` / `LESS_THAN` predicates, `sortColumn` / `sortOrder` honouring,
the batch body-size ceiling with a large `listQueues` page, and queue-name
uniqueness per broker are **not** verified by Phase 0. Slice 0 verifies them
against the `just up` dev pair, captures verbatim fixtures into
`src/test/resources/jolokia/`, and appends a section to
`docs/broker-management-notes.md` before any code depends on them. Tier B/C carry
a fallback branch (unfiltered page + Studio-side classification) if sort or the
numeric predicate is not honoured.

## Risks / Trade-offs

- **Slice 0 finds `sortColumn` / `GREATER_THAN` not honoured** → tier B falls
  back to an unfiltered page plus Studio-side classification from the last
  snapshot; the slice carries the branch, no redesign.
- **springdoc 3.1.0 rough against Boot 4.1** → ADR-0019's recorded fallback:
  hand-written types + a runtime boundary check on new endpoints; OpenAPI
  generation becomes a fast-follow.
- **`queue_snapshot` PK excludes `address`** → Slice 0 confirms queue-name
  uniqueness per broker against a multi-address fixture; if it can collide, a new
  changeset `010` widens the PK (changeset 005 stays untouched — non-negotiable
  #7).
- **Split-brain ratchet moved mid-phase** → `ScrapeCycleTest` covers
  first-sight / confirmed / planned-failover / per-cluster isolation, asserted
  against the retired `HaStateEvaluatorTest` cases for parity.
- **SSE chatter every tick** → the scheduler diffs persisted state and publishes
  only on a real change; the heartbeat is a comment, not data.
- **Settings area is broad** → it is the last slice and independently cuttable;
  Slices 1–8 deliver the phase's core without it.
- **Library APIs (React Flow 12, react-virtual 3, TanStack Table v9)** →
  pseudocode in the plan is illustrative; each signature is re-checked via
  `ctx7` during apply. ADR-0005 already sanctions all three libraries.

## Migration Plan

- Additive schema only: one new changeset `009-studio-settings.sql`; released
  changesets 001–008 untouched. `ddl-auto=validate` + the Testcontainers context
  test catch any entity/schema drift at startup.
- `scheduler/HaRefreshTask` and `HaRefreshTaskTest` are deleted; the new
  scheduler reproduces tier-A behaviour exactly (parity asserted in
  `ScrapeSchedulerTest`), so topology/health output is unchanged on day one and
  only gets fresher.
- Frontend: `PairSpine` deletion and the routerless→routed move happen inside
  Slice 3–4; each slice ends with `verify-web` green, so the app is never left
  half-migrated between slices.
- Rollback: the change is pre-release; revert the branch. No data migration to
  undo — `queue_snapshot` / `metric_sample` are disposable caches and
  `studio_setting` is additive.
- Deploy note: proxies must not buffer `GET /api/v1/stream`
  (`X-Accel-Buffering: no`) — added to `docs/architecture.md` and the compose
  files.
