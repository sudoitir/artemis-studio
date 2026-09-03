## 1. ADRs and conventions

- [x] 1.1 Write `docs/adr/0015-tiered-scrape-scheduler.md` — three fixed-delay tiers on a pooled virtual-thread `TaskScheduler`; one batched POST per node per tick; HTTP outside DB transactions; per-cluster refresh-cycle counter + one-cycle split-brain ratchet moved here from the retired `HaRefreshTask`; ~2-cycle detection ceiling preserved
- [x] 1.2 Write `docs/adr/0016-queue-snapshot-bulk-upsert.md` — scoped departure from ADR-0011 for `queue_snapshot` only: JDBC `INSERT … ON CONFLICT (node_id, queue_name) DO UPDATE` via `NamedParameterJdbcTemplate.batchUpdate`, per-node stale-row reap; estate tables stay pure JPA
- [x] 1.3 Write `docs/adr/0017-cross-node-aggregation.md` — one logical node per shared `NodeID`, scrape the live endpoint only; queue row keyed `(clusterId, address, queueName, routingType)` with per-node cells + rolled-up totals + `nodesPresent/nodesTotal`; unmanageable node = present-without-data; unreachable endpoint = last rows marked stale, not dropped
- [x] 1.4 Write `docs/adr/0018-sse-hub.md` — `GET /api/v1/stream` over `SseEmitter` (cite ADR-0010); topic model (`topology`/`health`/`queues`); poll-derived change signals only; heartbeat + `X-Accel-Buffering: no`; two-failure client fallback to polling. Add an "Annotated by ADR-0018" note to ADR-0003's `Flux<ServerSentEvent>` consequence with a backlink
- [x] 1.5 Write `docs/adr/0019-openapi-generated-frontend-types.md` — `springdoc` backend + `openapi-typescript` in the web build; hand-written DTO mirrors deleted; drift is a build failure; recorded fallback to hand-written types + runtime boundary check if springdoc is rough on Boot 4.1
- [x] 1.6 Update `openspec/project.md` roadmap line only if needed; leave README/architecture doc edits to group 12

## 2. Broker-surface verification spike (Slice 0)

- [x] 2.1 `just up`; seed ≥500 queues across ≥2 addresses and a JMS producer/consumer against `:61616`
- [x] 2.2 `curl` and capture verbatim into `src/test/resources/jolokia/`: `list-sessions.json`, `list-connections.json`, `list-producers.json` (empty options, `-1/-1`)
- [x] 2.3 `curl` and capture `list-queues-sorted.json` (`sortColumn=messageCount,sortOrder=desc`), `list-queues-gt.json` (`{"field":"messageCount","operation":"GREATER_THAN","value":"0"}`), `list-queues-page-500.json` (`pageSize=500`)
- [x] 2.4 Append "## 10. Phase 2 surface checks" to `docs/broker-management-notes.md`: verbatim output + a verdict table (sort honoured y/n, GT honoured y/n, batch ceiling, queue-name uniqueness per broker)

## 3. Tiered scheduler + limiter; retire `HaRefreshTask` (Slice 1)

- [x] 3.1 `config/ArtemisStudioProperties.java` + `application.yml` — add `spring.task.scheduling` pool config and `artemis-studio.metric.retention-days: 7`
- [x] 3.2 `scheduler/NodeScrapeLimiter.java` — per-node `Semaphore`, `@Scheduled(fixedRate=1000)` refill to the configured ceiling; `acquire(nodeId)` before every scheduler POST
- [x] 3.3 `scheduler/ScrapeCycle.java` — per-cluster `AtomicLong next(clusterId)`; per-`(cluster,node)` one-cycle split-brain ratchet (`evaluate(...)`) moved verbatim from `HaStateEvaluator`
- [x] 3.4 `domain/topology/HaStateEvaluator.java` — remove the mutable `firstSuspectedCycle` map; keep only pure derivations; update `HaStateEvaluatorTest` accordingly
- [x] 3.5 `broker/JolokiaBrokerClient.java` — add a batch-aware parsed accessor (`valueParsed` on a batch entry) and cache the resolved broker MBean name per node so a tick is one POST, not two
- [x] 3.6 `persist/QueueSnapshotEntity.java` + `QueueSnapshotRepository.java` (read-only JPA, `findByClusterId`); confirm mapping passes `ddl-auto=validate`
- [x] 3.7 `persist/QueueSnapshotUpsert.java` — `NamedParameterJdbcTemplate.batchUpdate` `INSERT … ON CONFLICT` + `reapStale(nodeId, sweepStart)` (ADR-0016)
- [x] 3.8 `persist/MetricSampleWriter.java` — append `subject_type=QUEUE` samples for `messageCount`/`consumerCount`/`messagesAdded`/`messagesAcked` on tiers B/C
- [x] 3.9 `persist/MetricSampleReaper.java` — `@Scheduled(cron nightly)` `DELETE FROM metric_sample WHERE ts < now() - retention`
- [x] 3.10 `scheduler/SweepCursor.java` — per-node page cursor for tier C (wraps at end; records sweep-start for the reap)
- [x] 3.11 `scheduler/ScrapeScheduler.java` — tier A (HA + topology + counters, one `batch()` POST), tier B (hot `listQueues` page), tier C (sweep page); per-node acquire→POST→parse→short `@Transactional` persist; per-node try/catch → `last_error`, never abort siblings; tier B/C fallback branch if Slice 0 showed sort/GT unhonoured
- [x] 3.12 Delete `scheduler/HaRefreshTask.java` and `src/test/java/.../scheduler/HaRefreshTaskTest.java`
- [x] 3.13 `NodeScrapeLimiterTest` — burst shaping, one slow node does not stall others
- [x] 3.14 `ScrapeSchedulerTest` (Mockito + `MockRestServiceServer`) — tier-A parity with the retired task, failing node isolated, no cross-node/cross-cluster abort, one POST per node per tier
- [x] 3.15 `QueueSnapshotUpsertTest` (Testcontainers) — insert then update same PK, reap by `ts < sweepStart`
- [x] 3.16 `MetricSampleReaperTest` (Testcontainers) — old rows deleted, newer kept
- [x] 3.17 `ScrapeCycleTest` — first-sight vs confirmed vs planned-failover cross-cycle non-escalation; per-cluster isolation

## 4. Cross-node read APIs (Slice 2)

- [x] 4.1 `broker/BrokerListOps.java` — one op / one page on one node; double-decode `{"data":[…],"count":N}`; string→typed scalar coercion helpers
- [x] 4.2 `service/CrossNodeAggregator.java` — build `QueueView` rows from `queue_snapshot` per ADR-0017 (pair dedup, per-node cells, totals, node presence, staleness)
- [x] 4.3 `service/PagedListService.java` — live-through fan-out: one batched POST per serving node, tag rows by logical node, merge, sort, paginate in memory; node down contributes nothing (not an error)
- [x] 4.4 `web/dto/ResourceViews.java` — `QueueView`, `QueueNodeCell`, `PagedView<T>`, `AddressView`, `ConsumerView`, `SessionView`, `ConnectionView`, `ProducerView` (shapes finalised from Slice 0 fixtures); `@Schema`/`@Operation` annotations
- [x] 4.5 `mapper/QueueViewMapper.java` + `mapper/ResourceViewMapper.java`
- [x] 4.6 `web/ClusterResourceController.java` — `GET /api/v1/clusters/{id}/{queues,addresses,consumers,sessions,connections,producers}` with `q`/`page`/`size`/`sort`; capability-gated (typed problem + `broker.xml` snippet when `MANAGEMENT_READ` not `AVAILABLE`)
- [x] 4.7 `CrossNodeAggregatorTest` — pair dedup, unmanageable node present-without-data, stale endpoint marked not dropped, one-node vs two-node queue
- [x] 4.8 `PagedListServiceTest` — one node down still returns the others, rows tagged by node
- [x] 4.9 `ClusterResourceControllerTest` (MockMvc, `@MockitoBean` broker connections backed by Slice 0 fixtures) — one endpoint per resource; capability-gated path

## 5. React shell: routing + generated types + tokens (Slice 3)

- [x] 5.1 `pom.xml` — add `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0`; expose `/v3/api-docs` (+ Swagger UI); confirm `./mvnw -q -DskipTests package` still builds
- [x] 5.2 `web/package.json` — dev-dep `openapi-typescript`; `gen:api` script; run it in `build`; commit a spec snapshot for CI
- [x] 5.3 `web/src/api/schema.d.ts` (generated) + rewrite `web/src/api/client.ts` to consume `paths`/`components["schemas"]`; delete every hand-written DTO interface; keep `ApiError` + query-key helpers
- [x] 5.4 `web/vite.config.ts` — wire `@tanstack/router-plugin/vite` before `react()`
- [x] 5.5 `web/src/routes/**` + generated `routeTree.gen.ts` — `__root`, `index` (redirect to first cluster), `clusters/$clusterId/route` (rail + Phase 1 detail/ledger header + `<Outlet/>`), children `topology` (default), `queues`, `addresses`, `consumers`, `sessions`, `connections`, `producers`, `settings`; per-route `errorComponent`; typed search schema for `q`/`sort`/`page`
- [x] 5.6 `web/src/main.tsx` — `RouterProvider` inside `QueryClientProvider` inside `MantineProvider`; router `context` carries the `QueryClient`
- [x] 5.7 `web/src/theme.css` — add `--as-grid-*`, `--as-graph-*`, `--as-alert-dot`, `--as-palette-bg` (from Mantine primitives, no hex); add the `:root[data-mantine-color-scheme='light']` override block
- [x] 5.8 Desktop-only — `AppShell` drops `collapsed`/`breakpoint`/`Burger`; app min-width ~1100px
- [x] 5.9 `verify-web` green; `gen:api` produces no diff on a clean tree

## 6. Topology graph (Slice 4)

- [x] 6.1 `web/src/topology/layout.ts` — pure function: logical nodes → React Flow nodes/edges encoding y = HA role, edge dash = replication behind, both-above = split-brain critical, offset = behind; deterministic, no dagre
- [x] 6.2 `web/src/topology/TopologyGraph.tsx` + node components (`BrokerNode`, `UnmanagedNode`) + `.module.css` — status word on every node, alert dots, short-NodeID/version badges, `tabIndex=0` + `aria-label` per node, visually-hidden cluster summary, 240ms cross-axis transition honouring `prefers-reduced-motion`; wire to `/topology` + `/health`
- [x] 6.3 `web/src/clusters/ClusterDetailPanel.tsx` — render `<TopologyGraph>` in the `topology` route; remove `<PairSpine>`
- [x] 6.4 Delete `web/src/clusters/PairSpine.tsx` and `PairSpine.module.css`; move its grammar doc-comment into `TopologyGraph.tsx`
- [x] 6.5 `layout.ts` unit test — healthy pair, replication behind, split-brain critical, unmanaged backup — `web/src/topology/layout.test.ts`, run via `npm test` (node --test, strip-types)
- [x] 6.6 `verify-web` green

## 7. Queue grid (Slice 5)

- [x] 7.1 `web/package.json` — add `@tanstack/react-virtual` (exact version via `ctx7`)
- [x] 7.2 `web/src/queues/QueueGrid.tsx` + `.module.css` — TanStack Table **v9** (`useTable` + `tableFeatures` + `createSortedRowModel` + `createColumnHelper<typeof features, QueueView>`; confirm API via `ctx7`) through Mantine `Table`; `@tanstack/react-virtual` row virtualization; server-side page/sort/filter bound to URL search params; tabular figures + `aria-sort` + sticky header + teaching empty state + skeleton past 1s + `nodesPresent/nodesTotal` column
- [x] 7.3 `web/src/queues/QueueDetailDrawer.tsx` — read-only per-node cell breakdown on row click
- [x] 7.4 Route `clusters/$clusterId/queues` wired to `useQueues(clusterId, {q,sort,page})`
- [~] 7.5 Component test (Testing-Library + MSW) — rows render, `aria-sort` flips on header click, empty state, skeleton→data — DEFERRED to Phase 3: needs a DOM test harness (vitest); its esbuild postinstall is blocked by this env's allow-scripts policy. Product verified via `verify-web` + manual smoke
- [x] 7.6 `verify-web` green

## 8. Remaining cross-node views (Slice 6)

- [x] 8.1 `web/src/resources/ResourceGrid.tsx` — column-spec-driven generic grid reusing the Slice 5 internals; a `Node` column on every view
- [x] 8.2 Routes + column specs for `addresses`, `consumers`, `sessions`, `connections`, `producers` wired to the live-through endpoints; capability note when `MANAGEMENT_READ` degraded
- [~] 8.3 `ResourceGrid` component test — column spec → headers + rows — DEFERRED with 7.5 (same harness)
- [x] 8.4 `verify-web` green

## 9. SSE hub + polling fallback (Slice 7)

- [x] 9.1 `sse/SseHub.java` + `sse/Subscriber.java` — `Map<UUID, Set<Subscriber>>`; `register`/`remove`; `publish(clusterId, topic)` filtered by subscriber topic set; `@Scheduled(fixedRate=20000)` heartbeat comment; dead-emitter removal via `completeWithError`
- [x] 9.2 `web/StreamController.java` — `GET /api/v1/stream?clusterId=&topics=` → `SseEmitter(0L)`; `X-Accel-Buffering: no`; `onCompletion`/`onTimeout`/`onError` deregister
- [x] 9.3 `scheduler/ScrapeScheduler.java` — after each tier tick, diff new vs previous persisted state (topology node set + roles; health enum; changed `queue_snapshot` PKs) and `hub.publish` only on a real change
- [x] 9.4 `web/src/api/stream.ts` — `useClusterStream(clusterId, topics)`: one `EventSource`, invalidate the matching TanStack Query key per event, two consecutive failures ⇒ stop reconnecting; extend `refetchInterval: 5000` to the new hooks
- [x] 9.5 Mount the stream hook in the cluster layout route; topology graph + queue grid patch live
- [x] 9.6 `SseHubTest` — topic filtering, dead-emitter removal
- [x] 9.7 `StreamControllerTest` (MockMvc async) — connect, receive a published event, disconnect deregisters
- [~] 9.8 Client test with a mocked `EventSource` — two errors → stops reconnecting — DEFERRED with 7.5 (same harness)
- [x] 9.9 `docs/architecture.md` + `deploy/compose/*.yaml` — proxy no-buffering note

## 10. Command palette (Slice 8)

- [x] 10.1 `web/src/palette/CommandPalette.tsx` — `@mantine/spotlight`; actions: jump to cluster, jump to view, jump to queue by name (searches loaded grid data), rediscover, register cluster, open settings; `mod+K`
- [x] 10.2 Mount once in `__root.tsx`
- [~] 10.3 Component test — open via shortcut, filter, invoking an action calls `navigate` — DEFERRED with 7.5 (same harness)
- [x] 10.4 `verify-web` green

## 11. Settings area (Slice 9 — independently cuttable)

- [x] 11.1 `src/main/resources/db/changelog/changes/009-studio-settings.sql` — `studio_setting (updated_at TIMESTAMPTZ, value JSONB, key TEXT PK)` with `--rollback`; add `<include>` to `db.changelog-master.xml`
- [x] 11.2 `persist/StudioSettingEntity.java` + `StudioSettingRepository.java`; passes `ddl-auto=validate`
- [x] 11.3 `service/SettingsService.java` — stored override else `ArtemisStudioProperties` default; typed getters (`tierA()`, `metricRetentionDays()`, `limiterPermits()`); `put(key, value)` with validation (positive intervals/ceiling)
- [x] 11.4 Wire scheduler `@Scheduled(fixedDelayString = "#{@settingsService...}")` and `NodeScrapeLimiter` ceiling and `MetricSampleReaper` window to `SettingsService`
- [x] 11.5 `web/SettingsController.java` + `web/dto/SettingsViews.java` — `GET /api/v1/settings` (effective value + override/default flag), `PUT /api/v1/settings`
- [x] 11.6 `web/ClusterController.java` + `ClusterService.java` — `PUT /api/v1/clusters/{id}/credentials` → `SecretVault.encrypt` + `BrokerCredentialEntity.replaceSecret`, audited in-transaction, no secret in response
- [x] 11.7 `web/src/settings/**` — route `/clusters/$clusterId/settings`: operational-config form, cluster-registration management (move `RegisterCluster.tsx`/`AddManagementUrl.tsx` flows here), credential-rotation form; typed cluster-name confirm on destructive/rotation actions
- [x] 11.8 `SettingsServiceTest` — seed-from-default, put-then-get, validation reject, SpEL cadence
- [x] 11.9 Credential-rotation controller test (Testcontainers) — rotate → decrypt → next `connections.forCluster` uses new creds; no secret in body; audit row pending→outcome
- [x] 11.10 `verify-web` green

## 12. Docs, verification, close-out

- [x] 12.1 `README.md` — tick the ten Phase 2 rows; replace the stale "Pre-alpha — workspace scaffold … no product features yet" status line; drop/replace the topology screenshot placeholder
- [x] 12.2 `openspec/project.md` — current-phase line → Phase 2 complete, Phase 3 next
- [x] 12.3 `docs/architecture.md` — reconcile the tier table with the implemented A + B-hot + C-sweep model; add the cross-node aggregation section (ADR-0017); fix the SSE `Flux` mention
- [ ] 12.4 `just fmt` (Spotless + `eslint --fix`) then `just verify` (`verify-api` + `verify-web`) — both green
- [ ] 12.5 Manual acceptance against `just up` with the dev pair under load: register `:8161` + `:8261`; topology graph orientation + failover flip within ~5s with NO critical split-brain + clean failback; queue grid = every queue across both nodes, sort/filter, smooth at ~1–3k rows; the five live-through views each = one POST per node per load; `GET /api/v1/stream` connected, depth moves live, stream kill → polling still updates; capability-degraded cluster shows the ledger not an empty grid; settings retention change trims `metric_sample`; credential rotation → next scrape green; broker load flat throughout
- [ ] 12.6 `/opsx:archive` — move the change to `openspec/changes/archive/`, merge `specs/` deltas into `openspec/specs/`; open the PR
