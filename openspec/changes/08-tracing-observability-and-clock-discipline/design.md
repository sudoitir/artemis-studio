## Context

Four clocks, three of which can be measured, and a sampler that could fail in
three different ways without saying anything. The design problem is less "how do
we correct time" than "what are we entitled to claim", and most of the decisions
below are about refusing to claim more than the measurement supports.

## Decisions

### 1. Measure the offset from a field already on the wire

Jolokia puts a `timestamp` on every response and the client was parsing and
discarding it. Bracketing each POST with the local clock gives
`offset = brokerMillis - (t0 + rtt/2)` for free, on every scrape, for every node
— with **no extra broker request**, which is the only version of this that
survives non-negotiable #1.

The alternative, an explicit time read per node per tick, is more precise and
costs a call per node per tick to learn a number that changes on the timescale of
NTP corrections. It was rejected on that ratio alone.

### 2. NTP's estimator, cut down to one sample per response

The lowest-RTT reading is the least distorted by queueing in either direction, so
only readings at or near the best round trip are allowed to teach the estimate;
the rest are dropped. What survives is smoothed by an EWMA so one outlier cannot
move a verdict. The best round trip decays slightly on each reading — without
that, one unusually fast early sample locks the filter shut permanently.

### 3. The uncertainty is not decoration

The source timestamp has second granularity, so a single reading is ±500ms before
the network is counted. Every estimate carries `rtt/2 + 500`, an offset inside its
own uncertainty is never corrected and never reported, and the default tolerance
is two seconds. This is the honest consequence of the measurement, and it means
sub-second skew is permanently invisible. Anything finer needs a different
measurement, not a smaller tolerance.

### 4. Normalise at the boundary, not at the reader

Three boundaries — the message expiry that becomes a deadline, the notification's
timestamp, and the message's own enqueue time. Correcting at the edge means a
wrong broker clock is absorbed once; correcting at each reader is how the
mixed-clock subtraction happened in the first place.

### 5. Forward skew only

The system cannot distinguish "produced in the future" from "produced, then sat on
the queue". A timestamp earlier than the observation is therefore not evidence of
anything and is never recorded as skew. Only a timestamp later than the moment the
message was read cannot be explained by residency.

### 6. Latency carries its provenance

`OBSERVED` is the gap between sample ticks and cannot resolve anything shorter;
`MESSAGE_TIMESTAMPS` is the real figure when both clocks can be trusted. Storing
which one produced a number is what makes the switch legible rather than a silent
shift in a chart — and a negative message-timestamp latency is refused outright,
because it is evidence of a disagreement, not a measurement.

A request whose own timestamp already failed the skew check is passed to the state
machine as `null`, so an untrustworthy clock is refused once at the boundary rather
than re-litigated inside the pure transition function.

### 7. The system's own clock, by corroboration

It cannot be measured against itself, so it is inferred: every measured node
disagreeing in the same direction has one plausible common cause, and it is not
every broker simultaneously. This needs at least two witnesses — a single-node
cluster can never reach the conclusion from its own evidence, and says the cheaper
thing instead.

Separately, comparing monotonic elapsed time against wall time catches a step
directly. Estimates are discarded rather than slowly unlearned, because every one
of them was measured against a clock that no longer exists.

### 8. Diagnostics as a ranked list with remedies, not a status code

The reasons are ordered most-likely-first and each carries what to do about it.
The sampling ceiling is always last and always present: it is the disclosed
consequence of ADR-0030's design, not a fault, and an operator who does not know
that reads an accurate empty list as a bug.

### 9. In memory, not in a table

Sampler health is broker-derived, disposable state about the last few seconds. It
follows the same rule as `queue_snapshot`: a restart losing it costs one sample
interval.

## Risks

- **Reported latencies change** where message timestamps are trustworthy. Mitigated
  by `latency_source` being visible per flow and in the API.
- **A proxy that strips the response timestamp** leaves the feature silently
  unavailable — reported as `UNKNOWN`, never as agreement.
- **The state-condition set is no longer the four ADR-0035 fixed.** That is what
  ADR-0053 exists to record.
