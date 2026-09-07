# ADR-0055: Metric charts carry time on the x-axis, and a relative window advances

- **Status**: accepted
- **Date**: 2026-09-06
- **Deciders**: maintainers

## Context

Studio's metric charts plot a pre-formatted timestamp string as a category axis:
each returned bucket is given one equal-width slot, in the order it arrived.

Two consequences fall out of that, and both of them are wrong in ways that matter
during an incident, which is when this page is read.

**A bucket that was never sampled does not exist on a category axis.** The
`metrics` capability requires that "a cold subject shows a gap, not a flat line",
and `connectNulls={false}` is already set — but it only handles a bucket present in
the response with a null value. `MetricQueryService` does not emit a row for a
bucket with no samples at all, so a five-minute scrape outage is not a null: it is
an absence, and the two buckets either side of it are drawn adjacent. The chart
reports a cluster that stopped being observed as a cluster that was quiet. This is
the exact failure mode the capability was written to prevent, and the axis is why
it survived.

**The right-hand edge stops moving.** `MetricsView` computed its `from`/`to` in a
`useMemo` whose dependencies do not change while the page is open, so a relative
range — `?range=1h`, the default — was fixed at the moment of navigation. The
15-second poll fired on schedule and re-requested that same historical window. An
operator watching depth climb was watching a still image being redrawn.

The two are one decision because fixing the second without the first produces a
chart that advances into a region where absence and quiet are still
indistinguishable.

## Decision

**We will position every metric sample by its timestamp, on a numeric axis with a
time scale**, rather than by its index in the returned sequence. A stretch of time
with no samples then occupies empty space proportional to its duration, which is
what the capability asks for and what an operator needs to see.

**We will advance the window of a relative range as time passes, quantized to the
bucket width in use.** The window's end becomes `floor(now / step) * step`; `from`
follows it, so the width is constant. An absolute range — one carrying explicit
`from`/`to` in the URL — never advances, because a deep link that drifts is not a
deep link.

**We will choose tick format and density from the range**, in one shared module, so
that the synchronised crosshair reads the same in every panel.

**We will render a failed read, a pending read and an empty window as three
distinct states at one fixed height.** "No samples in this window yet" was what an
operator saw when the endpoint returned 500.

## Consequences

- Sampling gaps that the product has always had become visible for the first time.
  Some clusters will look worse than the current chart claims. The current chart is
  the thing that is wrong; this is the point of the change, not a regression.
- The tooltip's label is now a number and must be formatted explicitly, and the
  axis needs a tick formatter. Both live in `web/src/metrics/axis.ts`, and the cost
  of forgetting one is a visible epoch integer, not a silent misreading.
- **Quantizing mints one query-cache entry per bucket.** A page left open for an
  hour on a 15-second bucket produces 240 keys. `gcTime` bounds it, and
  `placeholderData: (prev) => prev` keeps the transition from blanking, but a
  metrics view left open overnight is the case to watch.
- Not quantizing was not an option: a `to` of `Date.now()` mints a key per render.
- Pausing still does not take effect until the current interval timer fires, so one
  further poll can land after Pause is pressed. Closing that gap means cancelling
  in-flight fetches, which discards work the operator did not ask to discard and can
  leave a screen mid-update. The gap is bounded by the interval, the indicator reads
  `Paused` immediately, and the trade is recorded here rather than fixed.
- No backend change. `MetricQueryService` already clamps the bucket to the fastest
  sampling tier, caps a response at 500 points and reports what it used; the client
  was never in danger of a huge response, only of misdrawing a correct one.

## Alternatives considered

**Emit a null-valued row for every empty bucket, server-side, and keep the category
axis.** It would make `connectNulls={false}` do the work — but it inflates every
response by the length of the outage, spends the 500-point cap on nothing, and
still draws a seven-day window's ticks at fifteen-minute granularity. It fixes one
symptom of the axis rather than the axis.

**Interpolate across gaps.** Fastest to implement and the most dangerous thing this
page could do: it manufactures data for exactly the interval in which the product
had none.

**Advance the window continuously, on a one-second tick.** Correct-looking and
quietly expensive — a new query key every second, each a cache entry with its own
lifetime, for a chart whose data cannot change faster than its bucket.

**Refetch in place without changing the key, and shift the window server-side.**
Would require the endpoint to accept "the last hour" rather than a range, moving
the definition of *now* to the server and breaking the shareable absolute range that
uses the same endpoint.
