## Why

Pausing automatic refresh does not stop every screen. A hook has to opt in by wrapping its interval, and a hook with a literal interval, including every runtime plugin's, keeps polling while the header says `Paused`. A hook that does opt in still polls once more after the pause. The header also offers a refresh control that the stream, the intervals and resume-from-pause already cover, and it has no way to switch between light and dark, although both schemes are fully themed.

The cluster header's **Check** button is the only way Studio learns of a broker added after registration. ADR-0004 decided discovery would also run on a schedule, but that was never built.

## What Changes

- **Pause holds everywhere, immediately.** It is enforced once, through TanStack Query's `focusManager`, so every interval stops at its next tick, including plugins' and literal ones. The `poll()` helper is removed. ADR-0118.
- **BREAKING (UI):** the refresh control is removed from the header and the command palette.
- **A light/dark toggle** joins the header, with a matching palette command. The choice is remembered per browser. Dark stays the first-visit default.
- **Topology is rediscovered on a scrape tier** (`scrape.discovery-interval`, default 1m). ADR-0119.
- **BREAKING (API):** `POST /api/v1/clusters/{clusterId}/rediscover` is removed, together with the header's **Check** button and the empty topology's **Rediscover** button.

## Capabilities

### Modified Capabilities
- `data-freshness`: the refresh requirement is removed, and pause covers every periodic refetch and takes effect at once.
- `operator-ui`: the header offers a colour-scheme toggle.
- `cluster-topology`: rediscovery runs on a schedule, not on demand, and the empty state says so.

## Impact

- Web: `kernel/api/polling.ts`, `main.tsx`, `kernel/shell/{FreshnessBar,RootLayout,CommandPalette,ColorSchemeToggle}.tsx`, every hook that used `poll()`, `features/clusters/{ClusterHeader,TopologyCanvas,TopologyGraph,ClusterSettings,api}`.
- Backend: `platform/clusters/{ClusterService,ClustersModule,web/ClusterController}`, `platform/scrape/{ScrapeScheduler,ScrapeSettings,ScrapeProperties}`, `application.yml`, the OpenAPI contract.
- `docs/adr/0118-*`, `docs/adr/0119-*`. No new dependency.
