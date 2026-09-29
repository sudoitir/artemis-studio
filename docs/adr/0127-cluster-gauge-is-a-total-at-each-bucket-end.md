# ADR-0127: A cluster's gauge is the total at each bucket's end

- **Status**: accepted; supersedes decision 4 of
  [ADR-0110](0110-per-node-metric-series-and-flow-breakdown.md)
- **Date**: 2026-09-29
- **Deciders**: Mahdi Amirabdollahi

## Context

A cluster-scope gauge series (`messageCount`, `consumerCount`) averaged every queue and node
sample in a bucket. A cluster holding 10,000 messages across 50 queues showed a depth of about
200. ADR-0110 D4 recorded the defect: a total has to carry each queue's last value forward, because
a queue on the slow sweep is absent from most fine buckets.

## Decision

**We will read a cluster-scope gauge as the sum, over every queue on every node, of that queue's
last sample at or before each bucket's end, while that sample is within the queue's own sampling
interval.**

- A sample holds from its time until the queue's next sample, and for at most 1.5 times the gap
  since its previous sample, so a deleted queue stops counting after its own interval. A sample
  with no previous one uses two slow-tier intervals, which is also how far before the range the read
  looks for samples.
- The total has no bucket peak. The maximum of a sum inside a bucket is not the sum of the queues'
  maxima, and computing it would evaluate the total at every sample instant. The chart shows
  "Depth (total)" without the peak envelope.
- A bucket with no sample at all is left out rather than shown as zero.
- A queue-scoped series is unchanged: one queue's average and peak per bucket, split per node on
  request.

## Consequences

- The cluster's depth and consumer charts show real totals, and a slow-tier queue counts in every
  bucket until its interval lapses.
- A cluster total is a value at an instant, not an average over the bucket. A short spike inside a
  bucket is visible only on the queue's own chart.
- The 1.5 tolerance absorbs scrape jitter. A queue whose scrape is late by more than half an
  interval drops out of a bucket for that moment.

## Alternatives considered

- **Sum of per-queue bucket averages.** Rejected: a queue absent from a bucket still drops out, which
  is the defect.
- **Carry the last value forward without a limit.** Rejected: a deleted queue would count forever.
- **A join of every bucket with every sample.** Rejected: it grows as samples times buckets. Each
  sample instead generates only the bucket ends it covers.
