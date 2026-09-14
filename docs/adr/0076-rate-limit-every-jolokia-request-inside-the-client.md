# ADR-0076: Rate-limit every Jolokia request inside the client

- **Status**: accepted
- **Date**: 2026-09-14
- **Deciders**: maintainers

## Context

Non-negotiable #1 requires a per-node rate limiter so Studio is never the reason a broker
falls over. ADR-0015 introduced `NodeCallLimiter` and placed the permit at the call site:
each scrape tier, and later each on-demand path, calls `acquire(nodeId)` and then talks to
the broker. ADR-0025 kept that placement.

A call site acquires once per *logical operation*, but an operation can issue any number of
HTTP requests. An audit of every Jolokia caller found broker traffic the limiter never saw:

- By-id message operations take one permit, then send one request per id, up to the bulk
  cap of 1,000 or more with an override.
- Capture installation takes one permit, then sends 8–10 requests (address settings,
  security settings, address, queue, divert, verification).
- Cluster registration, rediscovery, node URL overrides and capability probes take no
  permit at all.
- Multi-step commands (a queue delete that reads bound queues first) send several requests
  per permit.

Each gap was a new caller that forgot, or a caller that grew a second request. That is a
failure of placement, not of any one caller.

## Decision

We will take the permit inside `JolokiaBrokerClient`, before every HTTP request it sends,
and remove every call-site `acquire`.

- **Key.** The permit is keyed by the Jolokia URL, which identifies one node. A client for a
  node that is not yet registered (registration, a probe, a URL override) is limited like
  any other.
- **Batches.** A batch request carrying many operations costs one permit per 50
  operations, rounded up, so batching cannot be used to exceed the ceiling.
- **By-id operations.** They are sent as batch requests of up to 50 operations. The broker
  has no documented message-id filter for its move, remove and expire operations, so
  each id is still one operation. What changes is that a thousand ids cost twenty
  requests, twenty permits, and twenty seconds at the default ceiling, not a thousand
  requests under one permit.
- **Waiting for a permit.** A timeout keeps the thread's interrupt status correct and
  surfaces as a stable connection-error kind.
- **Metrics.** Requests per node and permit wait time are exported as metrics.

The Core client path (browses, sends, capture drains, notification subscriptions) is not
Jolokia and is not counted by this ceiling. Its load is bounded separately, by bounded
browses and prefetch windows.

## Consequences

- No caller can reach a broker's management endpoint without a permit, including callers
  added later, because the client is the only way to send one.
- Multi-request commands take visibly longer at the ceiling. A capture install of about
  ten requests takes about half a second at 20/s. That is the ceiling doing its job, and
  those commands already report asynchronously per node.
- A by-id operation near the bulk cap takes seconds rather than being a burst. The bulk cap
  and dry run are unchanged.
- The limiter's bucket map is keyed by URL rather than node id. Removing a cluster forgets
  its URLs.
- ADR-0015 and ADR-0025 are superseded where they place the permit at the call site. Their
  scheduling decisions stand.

## Alternatives considered

- **Add `acquire` to each bypassing call site.** It fixes today's list and guarantees
  tomorrow's: the gaps were created exactly this way.
- **One permit per logical operation, with operations declaring their request count.**
  Every operation would have to count correctly and keep counting correctly. The client
  already knows.
- **A global ceiling across all nodes.** The broker is the thing being protected, and one
  busy node must not slow Studio's reads of a healthy one.
- **Replace per-id execs with one filter-based operation.** It would be cheaper where the
  broker supports it, but no message-id filter is documented for these operations, and a
  wrong filter is a destructive mistake.
