# ADR-0053: Broker time is normalised onto Studio's clock; skew is measured, disclosed and alerted

- **Status**: accepted
- **Date**: 2026-09-06
- **Deciders**: maintainer

## Context

A traced request-reply exchange involves four clocks — the broker's, the
producer's, the consumer's, and Studio's — and until now the codebase contained
no notion of any of them being different. There were zero uses of
`java.time.Clock` and fifty-three bare `Instant.now()` call sites.

That is fine while every instant is only ever compared with another instant from
the same source. Request-reply tracing is not that, and three real defects
followed:

- **A flow's deadline mixed two clocks.** `RrCorrelator.deadlineAt` took the
  absolute `JMSExpiration` — the *producer's* clock plus the TTL it asked for —
  and `RrDeadlineSweep` compared it against Studio's `Instant.now()`. A producer
  running ten minutes fast meant no flow ever timed out. Ten minutes slow meant
  every flow was `TIMED_OUT` the moment it was seen. Nothing reported either.
- **Latency was not latency.** The sampler stamped every observation with
  `Instant.now()` and never read the message's own timestamp, so `latencyMs` was
  the gap between two sample ticks: two messages seen on the same tick measured
  approximately zero, and the reported number was quantised to the sample
  interval with nothing saying so.
- **Two channels, two clocks, one flow.** Notification observations carried
  `_AMQ_NotifTimestamp` (the broker's clock); sampler observations carried
  Studio's. A completed flow could subtract one from the other.

The fourth scenario is the awkward one. If Studio's own clock is wrong,
everything above still "works" — consistently, and consistently wrong — and
Studio cannot measure itself against itself.

## Decision

We will **normalise every foreign timestamp onto Studio's clock at the boundary
where it enters**, measure the offset that makes that possible, and **disclose
and alert on** what cannot be corrected.

**Measure for free.** Jolokia puts a `timestamp` on every response and
`JolokiaResponse` was parsing and discarding it. `JolokiaBrokerClient` now
brackets each POST with Studio's clock and offers
`offset = brokerMillis - (t0 + rtt/2)` to a `ClockOffsetRegistry` keyed by
Jolokia URL — the same shared-state shape as the broker MBean-name cache, so no
call site changes and **no extra broker request is made**. The estimator is the
standard NTP one: the lowest-RTT reading is the least distorted, so only readings
at or near the best round trip teach the estimate, and what survives is smoothed
by an EWMA. The best round trip decays slightly each reading, or one lucky early
sample would lock the filter shut forever.

**Carry the uncertainty everywhere.** Jolokia's timestamp is in whole seconds, so
a single reading is ±500ms before the network is counted;
`uncertaintyMs = rtt/2 + 500`. An offset inside its own uncertainty is never
corrected and never reported — correcting by less than the error bar is noise
dressed as precision. The default tolerance is 2s for the same reason. A response
with no timestamp yields no reading, and the verdict is **unknown**, never zero.

**Normalise at exactly three boundaries**, so a wrong broker clock is absorbed
once at the edge instead of corrupting every downstream comparison:
`JMSExpiration` → the deadline (this is the deadline fix), `_AMQ_NotifTimestamp`
→ the notification's instant, and `JMSTimestamp` → a new `enqueuedAt` carried on
the sampler's observations. After this, every instant in `rr_flow` and `rr_event`
is on Studio's timeline.

**Producer and consumer skew is forward-only.** Their clocks reach Studio as
message timestamps. Studio cannot distinguish "produced in the future" from
"produced, then sat on the queue", so only a timestamp *later* than the moment
Studio read it is evidence. It is recorded on the flow and emitted as a
`CLOCK_SKEW` event naming which side.

**Latency states its own provenance.** `latency_source` is `MESSAGE_TIMESTAMPS`
when both messages carried a trustworthy enqueue time — the real figure — and
`OBSERVED` otherwise, with `latency_bound_ms` set to the sample interval and
surfaced as "± one sample interval". A negative message-timestamp latency says
the reply predates the request it answers, which is impossible: it is never
stored as a latency, the flow falls back to `OBSERVED`, and the disagreement is
recorded.

**Studio's own clock, by corroboration and by jump.** If every measured node in
the estate reports a same-signed offset beyond tolerance, the single common cause
is this host, not every broker simultaneously: verdict `STUDIO_SUSPECT`, worded
to send the operator to Studio's own host. Some nodes off and others in agreement
is `BROKER_SKEWED`, naming them. Separately, `MonotonicClockWatch` compares
`System.nanoTime()` against the wall clock every ten seconds; a divergence beyond
a second is a step, not drift, and every offset estimate is discarded rather than
slowly unlearned.

**It alerts.** `CLOCK_SKEW` joins the four state conditions ADR-0035 fixed,
seeded per cluster at `WARNING` and silenceable like any other rule. It is a
cluster-state fact of exactly the same kind — read from polled state, never from
a metric row — so it belongs with them rather than becoming a second mechanism.
Subjects are per node, plus `studio` for the corroborated verdict, so each tracks
independently.

**A `Clock` bean, injected narrowly.** Into the request-reply and skew path only,
not swept across all fifty-three `Instant.now()` sites: most of those only stamp
"when did this happen" and are indifferent to which clock said so. Where a
comparison between clocks *is* the computation, the seam exists and the
behaviour is testable.

## Consequences

- **The deadline bug is fixed**, and a skewed producer is now reported instead of
  silently making every flow time out or never time out.
- **Reported latencies will change** on clusters where message timestamps are
  trustworthy — from a sample-interval-quantised figure to the real one. That is
  a visible shift in a chart, which is why `latency_source` is stored per flow and
  exposed in the API: the change is legible rather than mysterious.
- **Skew below about two seconds is invisible**, and always will be with a
  second-granularity source. Anything finer needs a different measurement, not a
  smaller tolerance.
- **A proxy that rewrites or strips Jolokia's timestamp** leaves Studio with no
  reading. The verdict is `UNKNOWN` and nothing is corrected — correct, but it
  means the feature can be silently unavailable in some deployments.
- **`ADR-0035`'s closed set of four state conditions is now five.** That is the
  departure this ADR exists to record.
- **One more table to migrate and three more columns on the hot flow table.**
  `020-clock-skew-and-latency-source.sql`; released changesets untouched.
- The corroboration verdict needs at least two measured nodes. A single-node
  cluster can never conclude that Studio is the problem from its own evidence,
  and says so rather than guessing.

## Alternatives considered

- **Trust the brokers and ignore skew.** The status quo, and the source of the
  bug. A deadline computed from a foreign clock is not a deadline.
- **Query each broker's time explicitly** (an extra MBean read per node per
  tick). More precise, and a direct violation of non-negotiable #1 for a value
  already present in every response.
- **Refuse to use message timestamps at all** and keep observed latency. Honest,
  but permanently reports a number quantised to the sample interval, and the
  disclosure has to be built anyway.
- **Store broker time as-is and convert on read.** Pushes the correction into
  every reader, which is how the mixed-clock subtraction happened in the first
  place.
- **Alert through a metric threshold rule** rather than a new state condition.
  Skew is not a metric sample and has no series; forcing it into one would mean
  inventing a gauge purely to be thresholded.
