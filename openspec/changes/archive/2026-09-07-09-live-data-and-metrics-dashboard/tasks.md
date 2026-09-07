# Tasks

## 1. Decision records

- [x] 1.1 `docs/adr/0055-time-proportional-metric-charts.md` — the metric x-axis
      carries time, not array position; a relative window advances quantized to the
      bucket width. References ADR-0006, ADR-0033, ADR-0052.
- [x] 1.2 `docs/adr/0056-bounded-views-and-topology-level-of-detail.md` — every list
      view is bounded; the topology canvas degrades by level of detail above a node
      threshold. References ADR-0017, ADR-0020, ADR-0034.
- [x] 1.3 `docs/adr/README.md` index rows for both.

## 2. Refresh means operator-initiated refresh

- [x] 2.1 `web/src/api/polling.ts` — `refreshActiveQueries(qc)` returns the promise
      and passes `cancelRefetch: false`, so a second activation joins the running
      refetch rather than aborting it.
- [x] 2.2 `web/src/app/FreshnessBar.tsx` — the control's `loading` comes from a local
      flag held around that promise, never from `useFreshness().isFetching`; the
      control is disabled while it is set.
- [x] 2.3 Floor the busy state at a minimum visible duration so a fast refetch reads
      as an acknowledgement rather than a flicker. Clear it on unmount.
- [x] 2.4 `web/src/palette/CommandPalette.tsx` — the palette command goes through the
      same helper and inherits the behaviour.
- [x] 2.5 `web/src/app/FreshnessBar.module.css` — reserve width for the state label
      and the elapsed label, and put the elapsed numerals on tabular figures, so the
      header does not reflow once a second.

## 3. Pause holds, and can be seen

- [x] 3.1 `web/src/api/polling.ts` — a `mountRefetch()` helper through the same
      module flag, gated on stale-and-paused so a query that has never resolved
      still fetches once.
- [x] 3.2 `web/src/main.tsx` — apply it as the QueryClient's default
      `refetchOnMount`.
- [x] 3.3 `web/src/app/FreshnessBar.tsx` — the paused control renders a distinct
      variant, alongside the existing `aria-pressed` and the bar's `Paused` word.
- [x] 3.4 `web/src/app/useFreshness.ts` — read the query cache once per cache event
      instead of twice; this walk is the hot path at high query count.
- [x] 3.5 `web/src/api/polling.ts` — record the accepted ceiling: one further poll
      can land after pausing, because the interval is re-resolved when the current
      timer fires.

## 4. Metrics — correctness

- [x] 4.1 `web/src/metrics/ranges.ts` — the bucket width per range in one place,
      alongside the range list, so the window and the axis agree.
- [x] 4.2 `web/src/metrics/MetricsView.tsx` — replace the frozen `useMemo` window
      with one derived from a clock quantized to the bucket width, using the existing
      `useNow`. An absolute range stays fixed.
- [x] 4.3 `web/src/metrics/axis.ts` — one place for the numeric time axis: the
      recharts `XAxis` props, the tick formatter per range, and the tooltip label
      formatter, so every chart's crosshair reads identically.
- [x] 4.4 `DepthChart`, `ThroughputChart`, `ConsumersChart` — plot against epoch-ms
      with a numeric time scale, so an omitted bucket renders as proportional empty
      space.
- [x] 4.5 Value formatters and units on every series: depth compact, consumers
      integral, throughput per second.
- [x] 4.6 Each chart takes the query state, not only the series, and renders failure,
      loading and emptiness as three distinct states at one fixed height.
- [x] 4.7 `web/src/theme.css` tokens `--as-chart-grid` and `--as-chart-axis` are
      referenced by the charts rather than left dead while Mantine's defaults apply.

## 5. Metrics — the dashboard

- [x] 5.1 `web/src/metrics/StatRow.tsx` — current depth, added/s, acked/s and
      consumers as stat tiles with their movement across the window; tabular figures;
      a metric with no recent sample says so rather than showing zero.
- [x] 5.2 Each chart becomes a titled panel carrying its unit and its disclosures,
      rather than a bare label above a plot.
- [x] 5.3 A table view of the displayed window's buckets, so the view is usable
      without reading a plot.
- [x] 5.4 `?subject=` on the metrics route, passed through as a queue-scoped read;
      the view states the scope and offers a way back to cluster-wide.
- [x] 5.5 `web/src/queues/` — link a queue into its scoped metric view.
- [x] 5.6 Validate the resolved chart palette in both schemes with the dataviz
      validator, and resolve what it flags — in particular the two neutral series
      used together in the depth chart, and the status colour used as a series colour
      in the latency panel.

## 6. Bounded views

- [x] 6.1 `web/src/rr/FlowsView.tsx` — the pager the other paged views have.
- [x] 6.2 `web/src/events/EventsView.tsx` — the virtualised grid, keeping the live
      buffer and its existing cap.
- [x] 6.3 `web/src/audit/AuditView.tsx` — the virtualised grid.
- [x] 6.4 `web/src/dlq/DlqView.tsx` — bound the address list and expand a queue's
      per-node rows on demand rather than rendering all of them.
- [x] 6.5 `web/src/topology/TopologyCanvas.tsx` — above a node threshold, collapse a
      live/backup pair to one node and drop edge labels; render only visible
      elements; state that detail has been reduced.

## 7. Release hygiene

- [x] 7.1 `docs/dockerhub.md` — the Docker Hub page's own description: what it is,
      the screenshots, how to run it, the environment table, first login, and links
      back to the repository.
- [x] 7.2 `.github/workflows/ci.yml` — point the description push at that file, set a
      short description, turn on URL completion so the screenshots resolve, and drop
      `continue-on-error` so a failure is visible. The credential needs read, write
      and delete scope; it is the last step of the release job, so a failure there
      cannot burn a version.

## 8. Demo data and screenshots

- [x] 8.1 `deploy/compose/compose.demo.yaml` — a second live/backup pair, so the
      views show a four-node cluster.
- [x] 8.2 `scripts/demo-seed.sh` + `just demo` — register the cluster and drive
      traffic through the broker image's own client. No direct writes to
      `metric_sample`: the charts must show samples the product actually collected.
- [x] 8.3 `web/scripts/shots.ts` + `just shots` — capture the README images at a
      fixed viewport.
- [x] 8.4 Replace `docs/img/*.png`. Check each capture for the generated
      administrator password, hostnames and tokens before committing.

## 9. Tests

- [x] 9.1 The refresh control does not indicate busy on a background refetch, does on
      an activation, and a second activation while one is running issues no second
      refetch.
- [x] 9.2 Pausing stops intervals; a view opened while paused whose data is held does
      not refetch; a view whose data has never been fetched does; the control renders
      differently when paused.
- [x] 9.3 Resuming refetches once and clears the pending-change report.
- [x] 9.4 A series with an omitted stretch of buckets is separated in proportion to
      the time between the surviving points.
- [x] 9.5 A failed metric read renders the failure, not an absence of samples.
- [x] 9.6 A relative window's end advances by one bucket per bucket interval; an
      absolute window's does not.
- [x] 9.7 Queried by role and accessible name throughout, per the frontend contract.

## 10. Verification

- [x] 10.1 `just verify`.
- [x] 10.2 Contrast measured in both schemes for anything new, the light scheme
      measured rather than inferred.
- [x] 10.3 Against a running `just demo`, through `web/scripts/verify-live.ts`:
      pause, navigate three views, confirm no requests; resume, confirm one burst;
      leave the metrics view open across two bucket boundaries and confirm the
      window advanced by exactly one bucket.
