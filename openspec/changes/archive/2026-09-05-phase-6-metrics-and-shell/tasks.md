## 1. Metric partitioning (backend)

- [x] 1.1 Add Liquibase changeset `012-metric-partitions.sql` (daily partition DDL
      template + master changelog include). Do not edit changeset `005`.
- [x] 1.2 Implement `persist/MetricPartitionMaintainer.java`: creates today + 3 days'
      partitions (idempotent `CREATE TABLE IF NOT EXISTS ... PARTITION OF`), sets
      insert-tuned storage params on each, and drops fully-expired partitions via
      `DETACH CONCURRENTLY` then `DROP TABLE`. Scheduled daily.
- [x] 1.3 Narrow `persist/MetricSampleReaper.java`'s bounded `DELETE` to target
      `metric_sample_default` only.
- [x] 1.4 Integration test: force-run the maintainer against Testcontainers PG; assert
      today+3 partitions exist, an expired partition is dropped, and a concurrent insert
      during the drop succeeds.

## 2. Metric read API (backend)

- [x] 2.1 Implement `persist/MetricSeriesRepository.java` (JDBC, no JPA entity): gauge
      query (`avg`/`max` per `date_bin` bucket) and counter-rate query (per-subject
      `GREATEST(max-min,0)` delta summed, divided by bucket seconds).
- [x] 2.2 Implement `service/MetricQueryService.java`: metric-name → GAUGE/RATE lookup,
      step clamping (≤500 points, floor at tier-B interval), range clamping to
      `retentionDays`, echoes the step/range actually used plus an adjusted flag.
- [x] 2.3 Add `web/dto/MetricViews.java` (`MetricSeriesResponse`, `MetricSeries` with
      `kind`, `unit`, `points`) and `web/MetricsController.java`:
      `GET /api/v1/clusters/{clusterId}/metrics`.
- [x] 2.4 Unit test: counter reset across a bucket boundary yields rate `0`, never
      negative.
- [x] 2.5 Unit test: a request for a finer step than allowed returns the clamped step
      and `truncated: true`; a range beyond retention is clamped with the same flag.
- [x] 2.6 `./mvnw test` to regenerate `web/openapi.json` via `OpenApiSnapshotTest`.

## 3. Registration preview (backend)

- [x] 3.1 Change `RegisterPreview.nodeNames: List<String>` to
      `RegisterPreview.topology: TopologyView` in the DTO; update
      `ClusterService.checkConnection` to return the topology already produced by
      `TopologyDiscovery` instead of discarding it into a name list.
- [x] 3.2 `./mvnw test` again to confirm the OpenAPI snapshot picks up the shape change;
      `npm --prefix web run gen:api` to regenerate `schema.d.ts`.

## 4. Metrics UI (frontend)

- [x] 4.1 Add chart tokens to `theme.css` (`--as-chart-grid`, `--as-chart-axis`,
      `--as-chart-1..4`, `--as-chart-threshold`) with light-scheme overrides.
- [x] 4.2 Add `keys.metrics` and `useMetrics(clusterId, params)` to `api/client.ts`;
      `refetchInterval` only when the range is relative and open-ended, never the flat
      5s default; `placeholderData: (prev) => prev`.
- [x] 4.3 Add `metricsRoute` (`clusters/$clusterId/metrics`) to `router.tsx` with
      `validateMetricsSearch` (`range` enum default `1h`, or absolute `from`/`to`).
- [x] 4.4 Build `metrics/MetricsView.tsx` + `metrics/RangePicker.tsx`: range control,
      four charts (`DepthChart`, `ThroughputChart`, `ConsumersChart`, and the moved
      `LatencyPanel`) sharing one `composedChartProps={{ syncId }}`; empty/stale states
      per metric.
- [x] 4.5 Update `rr/LatencyPanel.tsx` to use the new `--as-chart-*` tokens instead of
      hard-coded `blue.6`/`yellow.6`/`red.6`.
- [x] 4.6 Add compact depth/throughput charts to `queues/QueueDetailDrawer.tsx` scoped
      to that queue, fixed 1h range, reusing `useMetrics`.
- [x] 4.7 Add `metrics` to the sidebar's `app/navItems.ts` (task 6.1).
- [x] 4.8 `metrics/MetricsView.test.tsx`: MSW-served fixed series; assert the range
      control updates the URL and a sampling gap renders as a gap, not an interpolated
      line.

## 5. Frictionless registration (frontend)

- [x] 5.1 Extract `topology/TopologyCanvas.tsx` (pure, takes a `TopologyLayout`) out of
      `topology/TopologyGraph.tsx` (keeps the data hooks, calls `layout()`, renders
      `TopologyCanvas`).
- [x] 5.2 Add `clusters/normaliseSeeds.ts`: token splitting (newline/comma/semicolon/
      whitespace), scheme/port/path defaulting, dedupe; returns both the normalised URL
      and the original token for error messages.
- [x] 5.3 Add `clusters/examples.ts`: three hand-built `TopologyView` fixtures (single
      broker, live+backup pair, 3-node cluster) with their hint text.
- [x] 5.4 Add `clusters/RegisterCanvas.tsx`: renders example cards (via
      `TopologyCanvas`) before a successful check, cross-fades to the real discovered
      topology after (respecting `prefers-reduced-motion`), dims and captions "changed
      since you checked" when the form is edited after a successful check.
- [x] 5.5 Update `clusters/RegisterCluster.tsx`: wire `normaliseSeeds`, show the
      "normalised to:" line, add the `shape` selection state, move Core
      credentials/TLS bundle behind a `<Collapse>` (auto-opened when the capability
      ledger reports a gap), lay out form + `RegisterCanvas` side by side, navigate to
      `/clusters/{id}/topology` on successful registration.
- [x] 5.6 Widen `RegisterClusterButton`'s modal to `size="xl"`.
- [x] 5.7 `clusters/normaliseSeeds.test.ts`: bare host → full URL; mixed-delimiter
      input; dedupe; a genuinely unparseable token still errors.
- [x] 5.8 `clusters/RegisterCluster.test.tsx`: MSW returns a 2-node preview; assert the
      canvas swaps from examples to the real topology, and that post-check edits mark
      it stale.

## 6. Advanced sidebar (frontend)

- [x] 6.1 Add `app/navItems.ts`: the 12 existing views (from `ClusterLayout`'s `VIEWS`)
      plus `metrics`, each with an icon and label.
- [x] 6.2 Add `app/useNavCollapsed.ts` (`useLocalStorage`, key `as:nav:collapsed`,
      `getInitialValueInEffect: false`).
- [x] 6.3 Add `app/NavItem.tsx`: icon + label row, `Tooltip` (position right, `openDelay
      350`, disabled when expanded), collapsed-state `aria-label`, health mark/monogram
      for cluster rows.
- [x] 6.4 Add `app/NavToggle.tsx`: real `<button>` with `aria-expanded`/
      `aria-controls`, bound to `⌘/Ctrl+B` via `useHotkeys`.
- [x] 6.5 Update `app/RootLayout.tsx`: two `AppShell.Section`s (cluster switcher +
      view nav) in the navbar, animate `navbar.width` between 264/64 keyed off
      `useNavCollapsed`, `transitionDuration` gated by `useReducedMotion`.
- [x] 6.6 Update `app/ClusterRailNav.tsx` and `clusters/ClusterRail.module.css` for the
      collapsed monogram + persisted health mark; extend (not replace) the existing
      active-state grammar (weight + inline-start border, never color).
- [x] 6.7 Delete `ClusterLayout.tsx`'s `.viewStrip`/`.viewTab` block and its CSS; remove
      the dead `addTarget`/`setAddTarget` state (no caller today) in the same pass.
- [x] 6.8 Add the view-nav CSS module (logical properties only): collapsed grid
      collapse, label fade shorter than the width transition, focus ring not clipped
      at 64px, `prefers-reduced-motion` override.
- [x] 6.9 `app/RootLayout.test.tsx`: toggle persists to `localStorage`; a remount
      starts collapsed with no flash; collapsed rows still expose an accessible name.

## 7. Docs and process

- [x] 7.1 Write `docs/adr/0033-metric-read-model.md`: `date_bin` bucketing, gauge vs.
      counter, the restart-safe rate clamp, the ≤500-point cap, why no canvas charting
      library.
- [x] 7.2 Write `docs/adr/0034-collapsible-sidebar.md`: supersedes the "no collapsing
      navbar, desktop-first" note in `RootLayout.tsx`'s docstring; retires the
      horizontal view strip.
- [x] 7.3 Update `README.md`'s Phase 6 checklist and `docs/roadmap.md` if its Phase 6
      row needs wording changes.

## 8. Verification

- [x] 8.1 `./mvnw verify` (Testcontainers PG; validates Liquibase incl. the new
      changeset).
- [x] 8.2 `npm --prefix web run typecheck` after `gen:api` — confirms every
      `RegisterPreview.nodeNames` consumer was updated.
- [x] 8.3 `npm --prefix web test`.
- [ ] 8.4 Manual walkthrough via `just dev`: zero-cluster registration through example
      → check → preview → register → land on topology; sidebar collapse/reload/
      tooltip/hotkey/focus-ring check; metrics dashboard crosshair sync; light-scheme
      pass on charts and rail.
- [x] 8.5 `just verify` and `just fmt` before committing.
