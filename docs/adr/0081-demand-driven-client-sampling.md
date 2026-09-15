# ADR-0081: Client activity is sampled on demand, under a lease, into a shared cache

- **Status**: accepted
- **Date**: 2026-09-14
- **Deciders**: maintainers

## Context

The Flow screen needs per-client rates: producer → address and queue → consumer. The broker
exposes only lifetime counters on producers (`msgSent`) and consumers
(`messagesAcknowledged`); Studio reads them live on request and stores nothing
(cross-node-resource-views). Only queue rates are stored (ADR-0033, tiers of ADR-0015).

Three forces conflict:

- **Broker load** (non-negotiable #1). Consumer and producer listings are among the largest
  management responses, so reading them for every cluster around the clock is the load Studio
  must never impose.
- **Multi-instance** (`docs/architecture.md`). One instance scrapes a cluster under
  `ClusterLock`, and every instance serves reads and SSE. State held in one instance's memory is
  invisible to the others.
- **Honesty.** A rate that cannot be computed must read as unknown, never zero.

## Decision

We will sample client activity **only while a cluster's flow is observed**, and share the
result through the database.

- **Demand.** A lease row per cluster (`flow_demand.observed_until`) is renewed by every flow
  read. An open flow view re-reads every 15 seconds, so the lease stays live while one is open.
  Sampling stops when the lease expires, which defaults to 60 seconds. The `flow` stream topic
  is not a demand signal: every cluster page subscribes to every enabled feature's topics.
- **Who samples.** The sampler takes `ClusterLock` scope `FLOW_SAMPLE` per cluster and skips
  the cluster when the lock is held elsewhere. It never overlaps its own previous sweep.
- **What it sends.** One bulk POST per serving node per sweep (scrape-scheduling):
  - `listProducers` and `listConsumers`. Their rows already carry client id, user, protocol and
    remote address, so sessions and connections are never listed.
  - The routing the flow graph draws, as further entries of the same POST: divert and bridge
    attributes through Jolokia pattern reads, `listQueues` filtered to store-and-forward,
    temporary and filtered queues, and the dead-letter and expiry addresses from
    `getAddressSettingsAsJSON("#")`. The platform queue sweep keeps none of these.
  - Each listing is page 1 with a size capped by `flow.maxRowsPerNode`, and truncation is
    reported.
  - All of it goes through the per-node ceiling (ADR-0076).
- **How rates are computed.** The sampling instance works out deltas per member id in memory.
  - A first sighting, a counter that went backwards, or a newly acquired lock yields "unknown".
  - A member that left is dropped, so its last interval goes uncounted. This undercount is
    accepted and stated.
- **What is stored.** Results are aggregated per (node, kind, client identity, address, queue)
  and replace that node's rows in `flow_client_edge` in one short transaction. Per-node
  coverage and errors go in `flow_node_sample`. Both tables are disposable caches.
- **Settings** (ADR-0025): sampling interval (default 15 s, minimum 10 s), per-node row cap,
  and lease length.

## Consequences

- No Flow observer means no extra broker traffic. With an observer, the added load is
  one bounded POST per serving node per interval.
- Every instance serves the same rates. After a lock move or restart, rates read "measuring…"
  for one sweep.
- On nodes above the row cap, the sampled subset is arbitrary, because Artemis 2.44 cannot
  sort listings server-side. The view states the coverage.
- There is no client-rate history. Adding one later would need retention and is a separate
  decision.
- Two new high-churn tables need the storage parameters of non-negotiable #7.

## Alternatives considered

- **Always-on client scrape tier**: continuous load on every cluster, whether or not anyone looks.
- **In-memory sampler on the scraping instance**: its results are invisible to other instances.
- **SSE subscribers as demand**: the cluster layout subscribes every page to every feature's
  topics, so sampling would never stop while any cluster page is open.
- **Browser-computed deltas from the existing list endpoints**: downloads full client sets
  every poll, gives each viewer different numbers, and multiplies broker load per viewer.
- **Summing counters per group**: the sum goes negative when a member leaves.
- **Walking every page per sweep**: violates one request per node and has no upper bound.
