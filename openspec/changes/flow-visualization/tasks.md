# Tasks

Delivered in four layers; each layer ends green (`just verify`) and is its own
conventional commit. Design refs are `design.md` D1–D10.

## 1. Groundwork

- [ ] 1.1 Confirm via ctx7 the `elkjs` version to pin, its worker entry for Vite, and the `layerChoiceConstraint` option key; confirm `useReducedMotion` in `@mantine/hooks` 9 and the React Flow 12 edge/`useStore` APIs used (D7, D8)
- [x] 1.2 Against the project's Artemis image (existing `ArtemisIntegrationTest`), record the JSON fields of `listProducers`, `listConsumers`, `listSessions`, `listConnections` used by the sampler, and the bulk-exec response's total count (D3)
- [x] 1.3 Accept ADR-0080 and ADR-0081 (status → accepted) and add them to the ADR index

## 2. Layer 1 — Kernel and platform hooks

- [x] 2.1 ~~`SseHub.hasSubscribers`~~ dropped: the cluster layout subscribes every page to every feature's topics, so demand is the flow read itself (D2)
- [x] 2.2 `platform/clusters/ClusterLock` scope `FLOW_SAMPLE` (D3)
- [x] 2.3 `platform/scrape/MetricSamples` latest rate with its sample timestamp per queue + test (D6)
- [x] 2.4 `feature/flow` module skeleton: `package-info.java`, `FlowModule` descriptor (settings, topic `flow`, permission reuse), `FlowFeature`, registration in `app/StudioFeatures`, `@ApplicationModuleTest`; `ModularityTest` and `BoundaryRulesTest` pass (D1)

## 3. Layer 1 — Schema and sampler

- [x] 3.1 Liquibase `db/changelog/feature/flow/` changesets for `flow_demand`, `flow_client_edge`, `flow_node_sample` with padding order, FKs, unique index, fillfactor and autovacuum parameters; include from the master changelog (D5)
- [x] 3.2 JPA entities / repositories (or JDBC upserts following `QueueSnapshotUpsert`) for the three tables (D5)
- [x] 3.3 Settings `flow.sampleInterval` (15 s, min 10 s), `flow.maxRowsPerNode` (5000), `flow.demandLease` (60 s) via `SettingDef` (D3)
- [x] 3.4 `FlowDemand`: every flow read renews the cluster's lease; the sampler reads only the shared lease, so any instance's reader keeps sampling alive (D2)
- [x] 3.5 `ClientSampler`: lease check, try-lock, no-overlap guard, one bulk POST per serving node with `listProducers` + `listConsumers` only, parse through `BrokerListOps` helpers (D3)
- [x] 3.6 Per-member delta engine: first sighting / backwards counter / lock acquired → null; departed members dropped; stalled after two sweeps; unit tests with a fake clock (D4)
- [x] 3.7 Aggregation by identity tuple and host-without-port; per-node replace transaction outside broker I/O; reaping; `flow_node_sample` coverage, truncation and error kind (unreachable, permission denied) (D4, D5)
- [x] 3.8 Publish `flow` topic only when persisted flow state changed (realtime-stream delta)
- [x] 3.9 Micrometer metrics `studio.flow.sample.*` and per-sweep debug log (D10)
- [x] 3.10 `FlowSamplerIT extends ArtemisIntegrationTest`: real producers/consumers → two sweeps → non-null rates, truncation reported at a low cap, no requests after lease expiry

## 4. Layer 1 — Graph assembly and API

- [x] 4.1 `FlowGraphService` produce / route (copy vs shared) / consume edges from samples, `queue_snapshot` and queue rates; rate source, `asOf`, tier-C average, stale marking (D6)
- [x] 4.2 Faults: no consumer with backlog, stalled, node unreachable, permission denied with `broker.xml` snippet, rate unavailable on this broker, sampling truncated (D6)
- [x] 4.3 Identity grouping `clientId | user | host` with fallback chain (D6)
- [x] 4.4 Ranking by in / out / backlog with log10 bucketing and name tie-break; limit clamp ≤ 200; totals over all paths (D6)
- [x] 4.5 Focus lens with 1-hop neighbourhood and `+hop`; focus-matches-nothing state (D6)
- [x] 4.6 `FlowGraphServiceTest` covering 4.1–4.5
- [x] 4.7 `web/FlowController` `GET /api/v1/clusters/{id}/flow` with parameter validation, cluster read permission, ETag/304; controller slice test; OpenAPI regenerated into `web/src/kernel/api/schema.d.ts`

## 5. Layer 1 — Screen shell and table

- [x] 5.1 Frontend feature `web/src/features/flow`: `feature.ts` (route, `validateSearch`, nav `observe` 15, `streamTopics.flow`), `api.ts` (`useFlowGraph`, `useClusterStream(['flow'])`), `FEATURE_IDS`, `app/features.ts`, test manifest
- [x] 5.2 Design pass with ui-ux-pro-max against `theme.css` and the topology screen (layer 1 reuses `--as-danger` / `--as-stale`; the `--as-flow-*` graph tokens land with the graph in 6.2–6.3, measured in both schemes)
- [x] 5.3 `FlowView` shell: toolbar (rank, group, layers, Graph|Table, limit), KPI strip with "totals over all paths", footer bound and freshness, banners for unreachable / permission / truncation
- [x] 5.4 `FlowTable` on the existing virtualised table: kind, source → target, rate + source + age, nodes, faults; sort announcement; "measuring…" never sorted as zero
- [x] 5.5 Empty, filtered-empty, focus-matches-nothing, loading placeholder states
- [x] 5.6 RTL tests by role: bound text, URL-restored state, measuring vs zero, banners, empty states
- [ ] 5.7 Commit layer 1 (`feat(flow): …`) after `just verify`

## 6. Layer 2 — Graph

- [x] 6.1 `layout.worker.ts` + `useElkLayout`: layered RIGHT, column constraints, model-order seeding, node-set signature keying; `layout.test.ts` (columns, rate-only update keeps positions) (D7)
- [x] 6.2 Node components (client pill, address tag with A/M badge, queue box with depth bar, consumer group ×N, remote/broker) with accessible names and focus ring (ui-ux-pro-max pass)
- [x] 6.3 Static `FlowEdge`: width tiers, idle dashed, rate label with tabular figures, measuring / not counted / stale, fault glyph + word
- [x] 6.4 `FlowCanvas`: React Flow with drag/connect off, controls, fit with maxZoom 1, refit on node-set change, LOD by zoom, dense mode with `onlyRenderVisibleElements` + `MiniMap` stated
- [x] 6.5 `FlowLegend` generated from exported node/edge marks, docked in view
- [x] 6.6 Path emphasis on hover/focus/select (others dimmed); focus via the inspector, the table and a find box; Esc clears. The "Focus flow on…" palette group is dropped: the palette renders on every cluster page, and a flow read there would renew the sampling lease with nobody watching (ADR-0081)
- [x] 6.7 `FlowInspector` (Overview with queue sparkline via metrics, Members with links to resources close flows, Routing); focus returns to node on close
- [x] 6.8 Screen-reader status summary; keyboard-only test (tab → Enter → Esc returns focus); Graph/Table parity test
- [ ] 6.9 Commit layer 2 after `just verify`

## 7. Layer 3 — Motion

- [x] 7.1 Speed buckets, dot counts and global budget as pure functions; `edgeEncoding.test.ts` (D8)
- [x] 7.2 `animateMotion` dots in `FlowEdge`, memoised on (path, bucket, dots); a test that a same-bucket refresh does not re-render the dots
- [x] 7.3 Pause control, `useReducedMotion` (no dots rendered), `document.hidden` and off-screen → `pauseAnimations()`; tests for reduced motion and Pause (operator-ui delta)
- [x] 7.4 Node enter/exit transform transitions (200 ms), disabled under reduced motion
- [ ] 7.5 Perf check: 200-edge canvas profile at 60 fps; record result in the PR
- [ ] 7.6 Commit layer 3 after `just verify`

## 8. Layer 4 — Routing layers

- [ ] 8.1 Layers parameter end to end (diverts, bridges, cluster on by default; DLQ/expiry, temporary, capture taps off)
- [ ] 8.2 Queue filters on route edges (not carried by `queue_snapshot`; read with the routing objects). Diverts from `RoutingService`: exclusive "reroutes" with bypassed marks, copy, filter/transformer glyphs, "not counted by broker", partial presence fault
- [ ] 8.3 Bridges: local vs remote destination node, Δ acknowledged rate from the bridge counter read in the same per-node sweep POST (bridge MBean names from a cached search), not-connected and partial presence faults
- [ ] 8.4 Cluster hops from `$.artemis.internal.sf.*` queues to receiving node; internal addresses/queues excluded otherwise
- [ ] 8.5 Wildcard matcher (default syntax) with stated assumption; `WildcardMatcherTest`
- [ ] 8.6 Anonymous producer node; temporary queues collapsed per client; capture taps marked when shown
- [ ] 8.7 DLA / expiry edges from address settings for shown addresses only, cached five minutes, through the limiter
- [ ] 8.8 Service tests for each routing edge kind; IT with an exclusive and a copy divert against the real broker
- [ ] 8.9 Commit layer 4 after `just verify`

## 9. Verification and docs

- [ ] 9.1 `just demo` walkthrough: no-consumer fault, stopped node unreachable, rates after ~15 s, reroute divert, cluster hop, layers, focus + reload, table parity, reduced motion
- [ ] 9.2 Close all flow views; confirm sampling stops within the lease (`studio.flow.sample.skipped{reason=no-lease}`)
- [ ] 9.3 Contrast check of new tokens in light and dark; keyboard-only pass
- [ ] 9.4 Site guide page for Flow with `just shots` screenshot
- [ ] 9.5 `openspec validate flow-visualization --strict` passes
