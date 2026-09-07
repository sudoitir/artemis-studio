## Why

Change 07 built the freshness indicator and the pause seam, and both work. What
neither of them survives is contact with a screen that is actually polling.

**The refresh button lies about what it is doing.** Its spinner is bound to
`useFreshness().isFetching`, which is true whenever *any* observed query fetches —
so on a cluster screen with a 5-second poll the icon swaps to a spinner every five
seconds, forever, whether or not anyone asked for anything. The one control whose
entire job is to say "I am fetching because you asked" says it continuously and
means nothing by it. Clicking it repeatedly is worse than useless:
`invalidateQueries` cancels in-flight refetches by default, so each click aborts the
request the previous click started.

**The pause button is invisible.** Paused and running render the same `subtle`
ActionIcon; only `aria-pressed` differs, and only a screen reader can see it. It
also does not hold: `refetchType: 'none'` marks queries stale rather than fetching
them, and TanStack's default `refetchOnMount` then fetches every one of them the
moment an operator navigates to another view. Pause covers intervals and nothing
else, which is not what "pause" means to the person who pressed it.

**The metrics page shows a frozen window and hides the gaps it is required to
show.** `MetricsView` computes `from`/`to` inside a `useMemo` whose dependencies
never change while the page is open, so a live range's window is fixed at the moment
the page loaded: the poll fires on schedule and re-requests the same historical
window, and the right-hand edge of every chart stops advancing. An operator watching
a depth chart during an incident is watching a still image that refreshes.

Underneath that, the x-axis is a pre-formatted string, so every returned bucket is
allotted one equal-width slot. A bucket the API omitted because nothing was sampled
does not exist on that axis at all — its neighbours simply sit next to each other.
The `metrics` capability requires the opposite in as many words: *"a cold subject
shows a gap, not a flat line"*. A category axis cannot express that, and the chart
currently reports a cluster that stopped being scraped as a cluster that was fine.
Adjacent to it, a metrics fetch that fails renders "No throughput samples in this
window yet" — an outage presented as a fact about the data, which the frontend
contract forbids in its own words: unreachable is not empty.

**Several views have no upper bound.** The virtualised grid exists and three views
use it. `EventsView` and `AuditView` put a whole page of rows in the DOM; `DlqView`
renders every address, every queue under it, and every per-node row with no paging;
`rr/FlowsTable` has no pager at all, so it shows one page and no way to reach the
next; and `TopologyCanvas` hands every node to React Flow at every zoom level. None
of this matters on a two-node dev pair and all of it matters on the clusters this
product is for.

## What Changes

**Refresh means operator-initiated refresh.** The control's busy state tracks the
refetch the operator started and nothing else; the freshness label continues to
report all fetching, because that is *its* job. A refresh already in flight absorbs
further clicks instead of restarting itself, so the control cannot be broken by
being pressed.

**Pause becomes visible and total.** The paused control is rendered distinctly, not
only announced. Pausing suspends refetch-on-mount as well as intervals, so
navigating between views while paused stays paused — with the deliberate exception
of a query that has no data at all, which fetches once, because showing an operator
an empty screen is not what they asked for when they paused a full one.

**The live metrics window advances.** It is recomputed as time passes, quantized to
the bucket width so the window moves once per bucket rather than once per second.
An absolute, deep-linked range stays fixed, which is the whole point of one.

**Charts move to a time-proportional axis.** Position on the x-axis becomes a
function of time rather than of array index, which is what makes an unsampled
stretch render as the gap the capability requires. Tick density and format follow
the range instead of stamping the same format on fifteen minutes and seven days.

**Charts distinguish failure from emptiness from loading**, at a stable height, so
the page does not jump as each resolves.

**The metrics page becomes a dashboard.** The values an operator reads first — depth
now, added and acked per second, consumers — are stat tiles above the charts rather
than something to infer from a line's right edge. Each chart is a titled panel
carrying its own unit and its own disclosures. The window's data is also available
as a table, because a rendered chart is not reachable by every operator.

**A metric series can be scoped to one queue from the URL.** The read endpoint has
always accepted a subject; nothing in the UI ever passed one.

**Every list view is bounded.** The remaining unbounded views move to the existing
virtualised grid or gain the paging the others already have, and the topology canvas
degrades by level of detail above a node threshold rather than by drawing everything.

## Impact

- **Modified specs**: `data-freshness` (the refresh control reports only what the
  operator started; pause is visibly distinguishable and covers mounts),
  `metrics` (the live window advances; the axis is time-proportional; failure is
  distinguished from emptiness; summary values and a table view), `operator-ui`
  (a view whose row count grows with the cluster is bounded).
- **New ADR-0055** — metric charts render on a time-proportional axis, with a live
  window that advances quantized to the bucket.
- **New ADR-0056** — every list view is bounded; the topology canvas degrades by
  level of detail.
- Frontend: `app/FreshnessBar.tsx`, `api/polling.ts`, `app/useFreshness.ts`,
  `main.tsx`, the whole of `metrics/`, `rr/LatencyPanel.tsx`, `rr/FlowsView.tsx`,
  `events/EventsView.tsx`, `audit/AuditView.tsx`, `dlq/DlqView.tsx`,
  `topology/TopologyCanvas.tsx`, `theme.css`, `router.tsx`.
- **No backend change.** No schema change, no new endpoint, no new permission — the
  subject-scoped read, the server-side bucket clamp and the point cap already exist.
- Out of band, in the same change because it is release hygiene the product's users
  see: the Docker Hub description push has failed on every release with `Forbidden`
  while `continue-on-error` hid it, and the README screenshots predate all of the
  above.
