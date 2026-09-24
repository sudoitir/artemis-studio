# ADR-0110: Per-node metric series and the flow breakdown

- **Status**: accepted
- **Date**: 2026-09-24
- **Deciders**: Artemis Studio maintainers

## Context

In a symmetric cluster the question behind most backlog incidents is **which node** the
messages are piling up on. A typical answer is a node that receives the producers but has
no consumers, while redistribution is off or slow.

Studio stores every sample per node:
- `metric_sample.node_id`;
- `flow_client_edge` and `flow_route` rows;
- queue snapshots.

But every read sums across nodes. The Flow view shows graph or table, never both with a
monitoring pane.

A related defect was found while designing this: the cluster-scope gauge series
(`messageCount` and the rest) is an average over every queue and node sample in a bucket,
not a total.

## Decision

1. **Metrics queries can be split by node**, with `splitBy=NODE`.
   - This first release allows the split only for a single queue (`subjectType=QUEUE`).
   - The response keeps its total series unchanged and adds one entry per serving node,
     named. A node with no sample in the window is listed as not sampled, never as zero.
   - The per-node reads use the same rows, index and bucketing as the total, so the nodes
     add up to it.
   - At most 16 nodes are returned. The step widens so that nodes × buckets stays under
     2,000, and the response states when either bound applied.
   - The metrics module gains a dependency on `platform.clusters` to name the nodes.
2. **Flow can break its figures down per node**, opt-in with `byNode=true`.
   - Edges gain per-node rates; queue and address nodes gain per-node backlog and
     consumers; broker nodes gain their own totals.
   - The breakdown is computed from the per-node rows before they are summed.
   - Edges with no per-node source (wildcard, dead-letter, expiry) carry none.
   - A node that did not answer is stale in the breakdown, never zero.
   - The default graph's payload and entity tag are unchanged.
3. **The Flow view gains a Split layout**: the graph beside a resizable monitoring pane,
   using Mantine's `Splitter`.
   - The pane follows the selection, and the selection is in the address.
   - **Now** is Flow's own data: per-node shares and the imbalance stated in words.
   - **Over time** is a panel the metrics feature contributes through a kernel slot,
     `flow.selection.panels`, so Flow neither imports metrics nor calls its endpoint.
   - Per-node trends are small multiples: one chart per node, labelled in text, on a shared
     scale. Studio's categorical palette is deliberately small, so they are not one line per
     node.
4. **The cluster-scope gauge defect is recorded, not fixed here.** A correct total needs a
   last-observation-carried-forward read over each queue's own sampling interval, because a
   slow-tier queue is absent from most fine buckets. That is a read-model change of its own.
   Until it is made, the cluster breakdown in the pane shows current per-node figures, not
   trends.

## Consequences

- An operator can see that 92% of a queue's backlog is on one node with no consumers in
  one glance, stated in words.
- Per-node series cost the same rows as the total. Nothing new is sampled, and no broker is
  asked anything more.
- Flow's response grows only when the Split layout asks for it.
- Per-client history remains out of scope (it has no stored series), and the pane says so.

## Alternatives considered

- **Flow fetching metrics itself.** It would duplicate the charts and ranges, and it would
  call an endpoint that answers 404 when the metrics feature is disabled.
- **One coloured line per node.** It runs out of categorical colours at five nodes, and
  colour would carry meaning (non-negotiable #6 and the frontend rules).
- **Fixing cluster gauges in this change.** It is a separate read model with its own
  correctness argument.
