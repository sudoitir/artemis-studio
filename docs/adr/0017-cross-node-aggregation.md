# ADR-0017: Cross-node resource aggregation — one logical node per NodeID, live endpoint only

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi

## Context

The MVP bar is "every queue across every node in one table". Phase 1 established
(and `docs/broker-management-notes.md` §2 verified) that a synced backup adopts
its primary's `NodeID`, so a live/backup pair is **one logical node with two
endpoints**, not two nodes. Phase 1 already keys topology by `NodeID`.

Nothing yet defines how a queue list — or an address / consumer / session /
connection / producer list — is merged across a cluster's logical nodes: what
the row key is, how a pair is counted once, what a row means when one endpoint
is unreachable, and how a discovered-but-unmanageable node (ADR-0013) appears.
There is no ADR and no `openspec/specs/` requirement for it.

## Decision

We will aggregate cross-node resource views on these rules:

- **One logical node per `NodeID`. Scrape the serving endpoint only.** A pair's
  backup endpoint is never enumerated for resources, so a queue on a live/backup
  pair is counted once.
- **A queue view row is keyed `(clusterId, address, queueName, routingType)`.**
  It carries a `perNode` list of cells (`nodeId`, `nodeName`, `messageCount`,
  `consumerCount`, `deliveringCount`, `scheduledCount`, `stale`, `lastSeenAt`),
  rolled-up totals across nodes, and `nodesPresent` / `nodesTotal`.
- **Addresses, consumers, sessions, connections, producers are live-through.**
  One batched Jolokia POST per serving node, each returned row tagged with the
  logical node it came from, then merged, sorted, and paginated in memory. These
  are not cached (they are volatile and read-mostly-once); queues are, because
  they need history and change-delta events.
- **A known-but-unmanageable node appears in the node dimension as
  present-without-data** — never zeroed counters, never omitted. The UI shows it
  as a node awaiting a management URL.
- **An unreachable serving endpoint keeps its last cached rows, marked `stale`
  with `lastSeenAt`.** A single failed scrape does not delete a node's rows; the
  stale-row reap only runs after a *successful* full sweep of that node
  (ADR-0016).
- **Management-read capability gates the views.** When a cluster's connection is
  not `MANAGEMENT_READ = AVAILABLE`, the endpoint returns the capability reason
  and the enabling `broker.xml` snippet (CLAUDE.md #5), and the UI renders that
  rather than an empty table.

## Consequences

- Queue counts are correct across a pair without the caller knowing which
  endpoint is live — the aggregator resolves it from persisted HA state.
- The `perNode` cell model makes "queue X is deep on node 1 but empty on node 2"
  visible directly, which is the cross-node insight the product exists to show.
- Live-through fan-out means a six-node cluster costs six batched POSTs per
  resource-view load. Acceptable: one POST per node satisfies non-negotiable #1,
  and these views are opened deliberately, not polled.
- `queue_snapshot`'s PK `(node_id, queue_name)` omits `address`. If a broker can
  host two same-named queues on different addresses, the cache key collides; the
  Phase 2 verification spike checks this and, if needed, a later changeset
  widens the PK (changeset 005 stays immutable per non-negotiable #7).
- Sessions / connections / producers had no Phase 0 shape verification; the
  Phase 2 spike captures fixtures before their DTOs are finalised.

## Alternatives considered

- **Cache all six resource types in snapshot tables.** Five more tables, five
  more upsert and stale-reap paths, caching data that is stale the instant it
  lands. The only gain would be SSE deltas for those views, which is Phase 4's
  job via real notifications. Rejected.
- **Enumerate every endpoint including backups and de-dupe rows afterward.**
  Doubles the broker load and needs a row-identity rule to de-dupe; scraping the
  live endpoint only is simpler and cheaper. Rejected.
- **Treat each endpoint as its own node in the views.** Contradicts Phase 1's
  `NodeID` identity model and would show every queue twice on a healthy pair.
  Rejected.
