## Why

Phase 5 (request-reply tracing) shipped; Phase 6 in `docs/roadmap.md` is "Metrics into
partitioned Postgres · Mantine charts dashboards · retention + partition job." Metric
*collection* already runs (`MetricSampleWriter` appends 4 samples/queue on every tier-B/C
scrape tick, `MetricSampleReaper` trims nightly) but `metric_sample` has no reader, no
partitioning beyond a single default partition, and no UI — an operator cannot see the
history Studio is already recording. Separately, two UX gaps compound the first-five-minutes
experience the MVP bar is built around: registering a cluster asks for a list of Jolokia URLs
with no picture of what will be discovered, and the 12 per-cluster views are a horizontal
tab strip that already overflows and cannot show more than a name. All three are bundled
into one phase because the sidebar work is the natural home for a new Metrics view, and
because both UX changes ride the same "make Studio's charts and topology renderer double as
onboarding UI" idea: the topology grammar the product already draws is reused, unmodified,
to preview a not-yet-saved cluster.

## What Changes

- Daily-partition `metric_sample` (create-ahead + retention drop), replacing the single
  default-partition strategy from changeset 005.
- Add a metric read path: `GET /api/v1/clusters/{clusterId}/metrics` — `date_bin` bucketing,
  gauge vs. monotonic-counter-to-rate distinction, server-clamped step/range, **BREAKING**:
  none (new endpoint).
- Add a cluster Metrics view (depth, throughput, consumers, RR latency — synced crosshair)
  and compact per-queue charts in `QueueDetailDrawer`.
- **BREAKING**: `RegisterPreview.nodeNames: string[]` → `RegisterPreview.topology:
  TopologyView`. `POST /api/v1/clusters?dryRun=true` response shape changes; no deployed
  API consumer exists outside this repo's own frontend, so no migration path is provided
  (project convention: no back-compat layers).
- Add example-topology cards and a live discovered-topology preview to the registration
  form, plus seed-URL normalisation (scheme/port/path defaults) shown back to the operator.
- **BREAKING**: Replace the per-cluster horizontal view-strip navigation
  (`ClusterLayout`'s `.viewStrip`) with a two-section collapsible `AppShell` sidebar (cluster
  switcher + view nav), collapsible to an icon rail with tooltips, state persisted in
  `localStorage`. No dual navigation is kept.

## Capabilities

### New Capabilities
- `metrics`: cluster and per-queue historical metric queries (partitioned storage, bucketed
  read API, retention/partition lifecycle) and the charts UI that consumes them.

### Modified Capabilities
- `cluster-registration`: the dry-run preview (`POST /clusters?dryRun=true`) now returns the
  discovered topology shape instead of a bare node-name list, and the registration UI
  requirement gains an example/preview visualization step before save.
- `scrape-scheduling`: `metric_sample` moves from a single default partition to daily
  create-ahead partitions with retention-based drop, superseding the interim bounded-DELETE
  behavior recorded for that table.

## Impact

- **Backend**: new `MetricPartitionMaintainer`, `MetricSeriesRepository`,
  `MetricQueryService`, `MetricsController`, `MetricViews` DTOs; `MetricSampleReaper`
  narrowed to the default partition only; `ClusterService.checkConnection` /
  `RegisterPreview` DTO change; new Liquibase changeset `012-metric-partitions.sql`;
  `web/openapi.json` regenerated.
- **Frontend**: new `metrics/` feature folder, `app/navItems.ts` + `NavItem`/`NavToggle`/
  `useNavCollapsed`, `clusters/RegisterCanvas` + `normaliseSeeds`, `topology/TopologyCanvas`
  extracted from `TopologyGraph`; `theme.css` gains chart tokens; `router.tsx` gains a
  `metrics` route; `rr/LatencyPanel` moves off hard-coded colors onto the new chart tokens.
- **Docs**: ADR-0033 (metric read model) and ADR-0034 (collapsible sidebar, supersedes the
  "no collapsing navbar" note in `RootLayout.tsx`'s docstring).
- **No new dependencies** — `@mantine/charts`, `@mantine/hooks`, `@xyflow/react`,
  `@tabler/icons-react` are already installed and cover every new surface.
