## Context

See `proposal.md` for motivation; requirements are in `specs/`. The current state that shapes
the approach:

- **Client data is live-only.** `feature/resources/PagedListService` lists connections,
  sessions, consumers and producers on request via `platform/broker/BrokerListOps.fetch`.
  Nothing samples or stores them, and they carry lifetime counters only
  (`ProducerView.messagesSent`, `ConsumerView.messagesDelivered/Acknowledged`).
- **Only queue rates exist**: `platform/scrape/MetricSamples.latestRateBySubject` over
  `metric_sample` (`messagesAdded`, `messagesAcked`), written by tiers B (15 s, busiest page)
  and C (5 min sweep). `queue_snapshot` gives address → queue membership.
- **Routing objects**: `feature/routing/RoutingService` merges diverts (`DivertView`: exclusive,
  filter, transformer, owner `MESSAGE_CAPTURE`, presence) and bridges (`BridgeView`: acked
  counter, connected, presence) across nodes.
- **Scrape rules** (`scrape-scheduling`): exactly one bulk POST per node per tick; per-node
  ceiling inside `JolokiaBrokerClient` (ADR-0076); broker I/O outside transactions;
  settings-driven cadence via `ScheduledJob` + `SettingDef` (ADR-0025). Artemis 2.44 fails
  on `sortColumn` and `GREATER_THAN` filters, so nothing can be sorted broker-side.
- **Multi-instance** is designed for (`docs/architecture.md`): one instance scrapes a cluster
  under `platform/clusters/ClusterLock`, every instance serves reads and SSE.
- **SSE**: `kernel/shell/ClusterLayout` subscribes every cluster page to the topics of every
  enabled feature, so a topic subscription is not evidence that a particular view is open.
- **Graph precedent**: `web/src/features/clusters` (React Flow 12.11.6, hand layout,
  ADR-0056 dense threshold, status summary, legend from exported marks, refit on node-set
  change).

## Goals / Non-Goals

**Goals:**
- Zero broker load from Flow when nobody observes it; bounded, batched load when someone does.
- Identical answers from every Studio instance.
- Server-side assembly and bounding: the browser never receives the full client set.
- Rate-only refreshes never move a node; motion costs are bounded independent of cluster size.

**Non-Goals:**
- Per-client rate history, image export, MCP tool, request-reply overlay, federation and AMQP
  broker connections (listed in the proposal).
- Custom wildcard syntax discovery (not readable over management; stated as an assumption).
- Any mutation from the Flow screen.

## Decisions

### D1. A new `flow` feature module owns sampling, assembly and the screen
Backend `feature/flow` with `allowedDependencies = kernel.core, kernel.plugin,
kernel.security, kernel.settings, kernel.jobs, kernel.stream, platform.broker,
platform.clusters, platform.scrape, feature.routing`. Frontend `web/src/features/flow`.
*Alternative*: extend `feature/resources`. Rejected: resources is request-scoped listing
with close actions; Flow adds a scheduled sampler, tables and a graph, which is a separate
capability that should be disableable on its own (ADR-0069/0070).

### D2. Demand is a lease row renewed by flow reads (ADR-0081)
`flow_demand(observed_until, cluster_id)`. Every `GET /flow` upserts `observed_until = now +
lease` (default 60 s); the flow view polls every 15 s, so the lease stays live exactly while a
view is open, whichever instance serves it. The sampler samples only clusters whose lease is live.
*Alternatives*: SSE subscribers on the `flow` topic. Rejected: `kernel/shell/ClusterLayout`
subscribes every cluster page to the topics of every enabled feature, so a subscriber means "a
cluster page is open", not "the flow view is open"; it is also invisible to other instances.
Always-on tier: rejected for broker load around the clock.

### D3. One batched POST per serving node per sweep, row-capped by page size
Sampler job `flow-sample` (`ScheduledJob.fixedDelay`, interval from `flow.sampleInterval`,
default 15 s, min 10 s). For each leased cluster it takes `ClusterLock` scope `FLOW_SAMPLE`
(try-lock; skip if held) and, per serving node on virtual threads, sends one bulk POST with
`listProducers` and `listConsumers` (page 1, size `flow.maxRowsPerNode`, default 5000).
Identity needs no session or connection listing: on Artemis 2.44 every producer row carries
`clientID`, `user`, `protocol`, `address`, `remoteAddress`, `msgSent`, and every consumer row
`clientID`, `user`, `protocol`, `queue`, `address`, `remoteAddress`, `filter`,
`messagesAcknowledged`, `messagesInTransit` (recorded by probe, task 1.2). The envelope's
`count` against rows returned gives per-node truncation.

The same POST carries the routing objects layer 4 draws, each one entry, so there is still one
request per node per sweep:
- divert and bridge attributes through Jolokia **pattern reads** (`BrokerMBeans.divertsPattern`,
  `bridgesPattern`), parsed with the existing `DivertRow.parse` / `BridgeRow.parse`. A pattern that
  matches nothing returns that entry alone as 404 and is read as "none" (probed on 2.44);
- `listQueues` filtered to store-and-forward queues (`name CONTAINS $.artemis.internal.sf`),
  temporary queues (`temporary EQUALS true`) and filtered queues (`filter NOT_EQUALS ""`), each
  capped at the row setting. The platform queue sweep drops internal queues and keeps neither the
  temporary flag nor the filter, so these cannot come from `queue_snapshot`;
- `getAddressSettingsAsJSON("#")` for the dead-letter and expiry addresses, as `DlqService` reads them.
Bridge and store-and-forward rates are per-object counter deltas, like client rates. Results are
persisted to `flow_route` (changeset feature-flow 0002). A sweep for a cluster does not start while the
previous one runs.
*Alternative*: paging through all rows. Rejected: breaks one-request-per-node, and an
unbounded walk is exactly the load non-negotiable #1 forbids. Truncation is stated instead
(the broker cannot sort, so the sampled subset is arbitrary, and the view says so).

### D4. Rates are per-member deltas, aggregated, persisted as latest state
The sampling instance keeps `previous counter + timestamp` per (node, member id) in memory.
Rate = Δcounter / Δt; a first sighting, a counter that went backwards (restart) or an
instance that just took the lock yields `null` ("measuring"). Departed members are dropped,
so the interval they were last active in is uncounted: a bounded undercount, stated in the ADR.
Stalled = unacked > 0 and ack delta 0 for two consecutive sweeps (in memory, persisted as a
flag). Per node, results are aggregated by (kind, client id, user, host-without-port,
protocol, address, queue) and written in one short transaction replacing that node's rows.
*Alternative*: summing group counters. Rejected, because a member leaving makes the sum
negative.

### D5. Schema: four disposable cache tables, padding-ordered, churn-tuned
Under `db/changelog/feature/flow/`:
- `flow_demand(observed_until timestamptz, cluster_id uuid PK)`.
- `flow_client_edge(sampled_at timestamptz, rate double precision NULL, unacked bigint,
  member_count integer, kind text, client_id text, user_name text, remote_host text,
  protocol text, address text, queue_name text, node_id uuid, cluster_id uuid,
  stalled boolean)`, with identity columns `NOT NULL DEFAULT ''` and a unique index on
  (cluster_id, node_id, kind, client_id, user_name, remote_host, address, queue_name).
- `flow_node_sample(sampled_at timestamptz, producers_seen integer, producers_total integer,
  consumers_seen integer, consumers_total integer, error text, error_kind text,
  node_id uuid PK, cluster_id uuid)`.
- `flow_route(sampled_at timestamptz, rate double precision NULL, counter bigint, kind text,
  name text, source text, target text, filter text, transformer text, node_id uuid,
  cluster_id uuid, exclusive boolean, connected boolean)` for diverts, bridges, store-and-forward,
  temporary and filtered queues and the dead-letter / expiry addresses (changeset 0002).
The sample tables: `fillfactor = 70`, per-table aggressive autovacuum (non-negotiable #7).
FKs to `cluster`/`broker_node` with `ON DELETE CASCADE`, following allowed dependencies.
Rows untouched for three sweeps are reaped; a cluster with an expired lease is cleared after
three intervals.

### D6. Graph assembly is stateless and server-side
`FlowGraphService.graph(clusterId, FlowQuery{lens, focus, rank, limit, groupBy, layers,
nodes})` reads `flow_client_edge`, `flow_node_sample`, `QueueSnapshots.forCluster`,
queue rates, and `RoutingService` diverts and bridges, then:
1. builds the routing model (produce, route with copy/shared and filter, divert with
   exclusive/copy, bridge local/remote, cluster hop from `$.artemis.internal.sf.*` queues,
   wildcard match via a small default-syntax matcher, DLA/expiry when that layer is on,
   temporary queues collapsed, anonymous producers, internal and capture objects excluded
   unless their layer is on);
2. derives faults;
3. ranks paths by the chosen measure, **bucketed to log10 steps with a name tie-break**. The
   ranking is stable across small changes without per-instance memory, which a hysteresis
   window would need;
4. applies focus (1 hop up and down, `+hop` widens) and the limit (clamped ≤ 200);
5. computes totals over all paths.
Response `FlowGraphView{nodes, edges, kpis, totals{paths, shown, clamped}, sampledAt,
measuring, nodeStates[]}`; each edge `{kind, source, target, rate|null, rateSource,
asOf, averagedOver|null, stale, faults[], label}`. `ETag` = hash(latest sampled_at, latest
queue sample ts, params) → 304 on unchanged polls.
DLA/expiry addresses come from address settings read only for the shown addresses, cached
five minutes; the layer is off by default. `MetricSamples` gains a read returning
(rate, latest ts) per queue so edges can state age and the tier-C average.
*Alternative*: browser-side joins over the existing list endpoints. Rejected, because it downloads the
whole client set and ranks after download.

### D7. Layout: elkjs layered in a web worker, keyed on the node set (ADR-0080)
`elkjs` layered algorithm, direction RIGHT, fixed columns via
`org.eclipse.elk.layered.layering.layerChoiceConstraint` (producers 0, addresses 1, queues
2, consumers 3, remote 4), model order considered and seeded from the previous layout's
vertical order. It runs in ELK's own worker (`elk-api` on the page posting to `elk-worker.min.js`, whose URL Vite emits) — never ELK's bundled build inside a worker of ours, which would try to start a nested worker and re-runs only when the sorted
node-id signature changes; rate-only refreshes reuse positions. Node entry/exit uses 200 ms
transform transitions, disabled under reduced motion.
*Alternatives*: the hand layout from `clusters/layout.ts`, rejected because it has no crossing
minimisation and fails beyond a few dozen nodes. `@dagrejs/dagre`: no layer constraints and
weaker port/ordering control. d3-sankey: rejected, because it cannot draw cycles (diverts can
loop) and has no interaction model.

### D8. Motion: SVG `animateMotion` dots, bucketed, budgeted, paused natively (ADR-0080)
Custom React Flow edge (`BaseEdge` + `getBezierPath`, as in React Flow's animated-edge
example) renders 1–4 circles with `<animateMotion>` along the path with staggered
`begin`. Rate maps to one of five speed buckets (log10); the edge component is memoised on
(path, bucket, dot count), so a refresh that keeps the bucket does not restart the
animation. A global budget of 400 dots is allocated by rate; the lowest-rate edges lose
dots first. Width tiers: idle (dashed), light, busy. Pause, reduced motion
(`useReducedMotion` from `@mantine/hooks`, confirmed at apply), `document.hidden` and an
IntersectionObserver on the canvas call `pauseAnimations()` / `unpauseAnimations()` on the
edges' SVG root. Under reduced motion the dots are not rendered at all. Divert edges and
null-rate edges never animate.
*Alternatives*: CSS dash animation (`animated` edges), rejected because it reads as "selected"
rather than "flowing" and collides with the divert dash style. Canvas particle overlay:
rejected, because it adds a second rendering system and loses per-edge accessibility.

### D9. Screen composition reuses the kernel and existing UI
`feature.ts` (route `flow` under `clusterRoute`, `featureView`, `validateSearch` for `tab,
lens, focus, rank, limit, groupBy, layers, nodes`; nav `observe` order 15; `streamTopics.flow` invalidates). `useFlowGraph` = TanStack Query + `poll(15_000)`
+ `keepPreviousData`; `useClusterStream(['flow'])` is the demand signal. Components:
`FlowView` (toolbar, KPI strip, footer bound), `FlowCanvas`, `nodes/*`, `FlowEdge`,
`FlowLegend` (from exported marks, as in topology), `FlowInspector` (Overview, Members,
Routing; member rows link to the resources route that owns the `ConfirmByTyping` close),
`FlowTable` (existing virtualised table). LOD by zoom through a React Flow store selector;
above the ADR-0056 threshold: `onlyRenderVisibleElements`, `MiniMap`, stated. Tokens
`--as-flow-edge`, `--as-flow-edge-busy`, `--as-flow-dot`, `--as-flow-fault`,
`--as-flow-dim`, `--as-flow-lane` in both schemes, contrast measured.

No command-palette group: the palette renders on every cluster page, and a flow read
from it would renew the sampling lease while nobody is watching (ADR-0081).

### D10. Observability of the sampler itself
Micrometer `studio.flow.sample.duration` (timer, tag cluster), `studio.flow.sample.rows`,
`studio.flow.sample.truncated`, `studio.flow.sample.skipped` (tag reason: `no-lease`,
`lock-held`, `overlap`). A debug log line per sweep.

## Risks / Trade-offs

- [Row cap samples an arbitrary subset on huge nodes] → truncation is stated per node;
  focus lens can re-query with an `EQUALS` address filter in a later iteration (exec filter
  `EQUALS` is documented; not needed for correctness now).
- [Lock move or restart resets rates for a sweep] → "measuring…" instead of wrong values;
  specified.
- [Departed members' last interval is uncounted] → bounded undercount of one interval per
  departure; stated in ADR-0081 and the rate-source tooltip.
- [Wildcard syntax may be customised] → default-syntax assumption stated in the view.
- [Many SMIL animations cost CPU] → dot budget, off-screen pause, LOD removes dots below 0.35
  zoom, reduced-motion renders none; perf check in tasks.
- [ELK layout latency on 200 nodes] → worker thread; layout only on node-set change; a
  placeholder holds the frame meanwhile.
- [Address-settings reads for DLA/expiry add load] → off by default, only shown addresses,
  cached five minutes, through the limiter.
- [Consumer/producer JSON field names differ across broker versions] → a missing counter
  yields "rate unavailable on this broker"; an integration test pins the fields on the
  project's Artemis image.

## Migration Plan

Additive. New Liquibase changesets create the three tables; no backfill (the tables are
caches that fill on first observation). Rollback: disable the `flow` feature (its topic,
settings and routes disappear, sampler stops) or roll back the changesets with
`just db-rollback`. No existing API changes shape.

## Open Questions

- Exact `elkjs` version to pin, and whether Vite's `?worker` import or elkjs's bundled worker
  build is used. This is resolved via ctx7 at apply and changes neither the specs nor the tasks.
