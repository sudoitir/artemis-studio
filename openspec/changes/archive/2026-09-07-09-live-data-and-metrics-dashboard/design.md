## Context

Four facts about the current code shape this design.

1. **The pause seam already exists and is the right shape.** `api/polling.ts` holds
   one module-level flag, and `poll(ms)` turns every `refetchInterval` literal into
   a pausable one without per-hook state. Anything else pause needs to cover is
   another function through the same flag, not a new mechanism.
2. **Freshness is derived from the query cache, globally.** Nothing is wired per
   screen, so anything the indicator reports it reports everywhere. That is a
   virtue for the *label* and a defect for the *button*: the two need different
   scopes from the same source.
3. **The metrics backend is already careful.** `MetricQueryService` clamps the
   bucket to the fastest sampling tier, caps a response at 500 points, clamps the
   range to retention, and reports what it actually used via `step` and
   `truncated`. The client does not need to defend against a huge response; it
   needs to stop misrepresenting a correct one.
4. **`useMetrics` already takes its cadence from the caller** and already carries
   `placeholderData: (prev) => prev`, so a changing query key cross-fades rather
   than blanking. An advancing window is a change to what key is computed, not to
   how the query behaves.

## Goals / Non-Goals

**Goals.** A refresh control that means one thing. A pause that holds across
navigation and can be seen. Charts whose x-position is time, whose right edge
advances, and which cannot present an outage as a quiet period. A metrics page an
operator can read the answer off without interpreting a line. No view whose DOM
grows without bound with the cluster.

**Non-Goals.** Changing any polling cadence, or any server-side aggregation.
Persisting pause across a reload — ADR-0052 settled that and it is still right.
Client-side downsampling; the server's point cap is the bound. A charting library
change; `@mantine/charts` over recharts stays (ADR-0005).

## Decisions

### 1. The button's busy state and the label's busy state are different facts

`isFetching` from the query cache answers "is anything fetching", which is what the
freshness label should say. The refresh control must answer "is the refetch you just
started still running", which nothing in the cache can distinguish, because a
manual invalidation and a scheduled one produce identical query states.

So the control owns a local flag around the promise `invalidateQueries` returns.
The alternative — filtering the cache for queries invalidated within the last N ms —
is a heuristic reconstruction of something we already know exactly, and it fails
the moment a background poll happens to land in the same window.

`cancelRefetch: false` goes with it. TanStack's default cancels the in-flight fetch
and starts a new one, which is right for an invalidation that follows a mutation
(the in-flight response is known-stale) and wrong for a refresh button (the
in-flight response is exactly what was asked for). With it, a second click joins the
first, and the disabled control makes even that unreachable. No debounce, no
throttle, no timer: the state that already exists is the interlock.

A minimum visible duration on the spinner is deliberate. A refetch that completes in
40ms otherwise produces a flicker an operator reads as "nothing happened", and the
control's only job is acknowledgement.

### 2. Pause covers `refetchOnMount`, with one exception

Pausing intervals but not mounts means an operator who pauses and then navigates has
silently unpaused. Both go through the same flag: `refetchInterval` via `poll()` as
today, `refetchOnMount` via a sibling helper on the QueryClient defaults.

The exception is a query with no data at all. Blocking its first fetch renders an
empty screen and calls it paused, which is not what the operator asked for — they
paused a screen showing data, to stop it moving. So the gate is "stale, and paused",
never "paused" alone. A query that has never resolved is not stale; it is absent,
and absence is not something pause has an opinion about.

### 3. Pause is announced by state, shown by variant, and never by colour alone

The control renders a different Mantine variant when paused, and the bar beside it
already says the word `Paused` and already carries `data-state` on its dot. Three
independent carriers, none of them colour on its own, per the frontend contract.

### 4. The live window advances, quantized to the bucket

The frozen window is a `useMemo` whose dependency list is honest — nothing it
depends on changes — so the fix is to depend on time. Depending on time *directly*
mints a new query key every render tick, and every key is a new cache entry with a
new `gcTime`; a metrics page left open would accumulate one entry per second.

Quantizing to the bucket width makes the key change exactly as often as the data
can: once per `step`. Between boundaries the key is stable and the existing poll
refreshes the same window in place, which is the correct behaviour for the current,
still-filling bucket. `from` moves with `to`, so the window's width is constant.

An absolute range — `?from=&to=` — is exempt. It is a deep link to a moment, and a
deep link that drifts is not one.

### 5. The x-axis carries time, not array position

This is the load-bearing decision. A category axis allots one equal slot per
returned point, so a bucket the server omitted (nothing sampled) is not narrower or
marked — it does not exist, and the two buckets either side of a scrape outage are
drawn adjacent. The `metrics` capability requires a visible gap there, and no amount
of `connectNulls={false}` produces one, because there is no null: there is no row.

A numeric axis with a time scale places every point by its timestamp, so an
unsampled stretch is empty space of proportional width, which is both what the
capability asks for and what the operator needs to see. `xAxisProps` passes through
to the underlying `XAxis`, so this costs no new dependency and no fork of the
chart components.

Consequence, accepted: the tooltip's label is now a number and must be formatted,
and the tick format has to be chosen per range rather than baked in. Both are small,
and both were latent bugs anyway — a fifteen-minute window at fifteen-second buckets
was stamping `MMM D HH:mm` on sixty ticks.

### 6. Failure, emptiness and loading are three renders, at one height

"No samples in this window yet" is currently what an operator sees when the metrics
endpoint returns 500. Presenting an absence as a fact is the specific thing the
frontend contract forbids, and it is most dangerous exactly here, because a flat
empty chart during an incident reads as "the cluster is quiet".

All three states occupy the chart's height so that resolving one does not move the
page under a pointer that is already travelling toward something.

### 7. The numbers go above the charts

An operator arriving at this page wants four numbers — depth, in, out, consumers —
and currently has to read them off the right-hand edge of three lines. A headline
value is a stat tile, not a chart; the charts stay for the shape, which is what
they are good at.

### 8. Bounded views: reuse the grid, do not invent a second one

`VirtualTable` already carries paging, sorting, sort announcement, node attribution
and a reveal panel for clipped cells, and `operator-ui` already requires new tabular
views to use it. `EventsView` and `AuditView` predate the requirement rather than
disagreeing with it, so this is adoption, not design.

The topology canvas is the one that needs a decision, because there is no paging a
graph. Above a node threshold it collapses each live/backup pair into a single node
and drops edge labels; node internals render only above a zoom threshold. The
alternative — refusing to draw a large cluster — fails the operator who most needs
the picture.

## Risks / Trade-offs

- **One more poll can land after Pause is pressed.** TanStack re-resolves
  `refetchInterval` when the current timer fires, so a 5s interval can deliver one
  further refetch up to 5s after the flag flips. Closing it means cancelling
  in-flight fetches, which discards work the operator has not asked to discard and
  can leave a screen mid-update. The gap is bounded, invisible in the indicator
  (which reports `Paused` immediately), and cheaper than the fix.
- **A quantized key mints one cache entry per bucket.** Bounded by `gcTime`, but a
  page left open for hours on a 15-second bucket is the case to watch.
- **A time axis exposes sampling gaps that were previously invisible.** That is the
  intent, and it will make some existing clusters look worse than the current chart
  claims. The current chart is the thing that is wrong.
- **Level-of-detail hides real structure at high node counts.** Mitigated by making
  the threshold visible in the UI rather than silent — an operator must never
  wonder whether they are looking at everything.
