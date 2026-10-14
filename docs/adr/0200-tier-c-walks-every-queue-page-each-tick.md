# ADR-0200: Tier C walks every queue page of a node in each tick

- **Status**: accepted; amends [ADR-0015](0015-tiered-scrape-scheduler.md)
- **Date**: 2026-10-14
- **Deciders**: Mahdi Amirabdollahi

## Context

ADR-0015 had tier C read one `listQueues` page of 200 per node per tick, advancing an in-memory sweep cursor
until the whole queue set was covered. At the default 5-minute interval a node with 1,000 queues was fully
known after 25 minutes and one with 10,000 after more than four hours (`docs/sizing.md`): a queue created on
the broker was missing from Studio's lists for that long, and a removed one lingered as long. The load test had
to shorten the interval to 15 s to finish a run. The cursor also lived in one replica's memory, so a tick that
ran on another replica (tier C is an installation-wide job) restarted the walk from page 1.

## Decision

- **Each tier-C tick reads every page of every node**, one after the other, and reaps the rows the walk did not
  touch once the last page is in. Each page is still one POST through the node's rate limiter (ADR-0015's
  batching and the per-node limit hold), and is written as it arrives, so memory holds one page.
- **A walk that fails or is interrupted part way reaps nothing**; the next tick starts over. The job runner's
  timeout (ADR-0194) bounds a walk.
- **The sweep cursor is removed.**

## Consequences

- A node is fully known one tick after it is registered, whatever its size.
- Each tick sends `ceil(queues / 200)` calls per node in a burst instead of one: 50 for 10,000 queues, two
  seconds at the 25 calls/s node limit. Tier A and B calls wait behind them in the same limiter for that time.
- Replicas no longer disagree about where a walk stands.
