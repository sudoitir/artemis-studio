# ADR-0178: Config diff compares every node against the majority

- **Status**: accepted; amends [ADR-0043](0043-broker-configuration-comparison.md) D1 (what is compared) and D5 (when no diff is shown)
- **Date**: 2026-10-06
- **Deciders**: Mahdi Amirabdollahi

## Context

ADR-0043 compares two nodes: a `left` and a `right`, defaulting to an HA pair. On a cluster of
more than two nodes that hides drift on every node outside the chosen pair, and it cannot say which
node is wrong. When two nodes disagree, nothing in a pair says which one is the odd one out; with
four nodes and one stray `max-size-bytes`, the operator has to try every pair to find it. The view
also listed every key by default, so a healthy cluster opened on hundreds of rows and the one that
mattered was a search away.

ADR-0043 also decides that when either side cannot be read, no per-key diff is shown at all. That
was right for two sides, where the survivor has nothing to be compared with. With more nodes it
withholds a comparison that three answering nodes can still make.

## Decision

**We will compare every manageable node of the cluster at once, and report each key against the
value most nodes hold.**

1. **One read, every node.** `GET /clusters/{clusterId}/config-diff` reads each node of the cluster
   with one batched request through the per-node rate limiter, as before, and has no `left` or
   `right`. An optional `nodes` parameter narrows the comparison to at least two chosen nodes.
2. **A majority per key.** The majority value of a key is the value held by strictly more than half
   of the nodes that return the key. A node that answered but does not have the key is *missing*
   there, never an empty value. The nodes that do not hold the majority value, including nodes
   missing the key, are the key's outliers. With no majority the key says so and lists each
   distinct value with its nodes; it names no outliers.
3. **One state and one class per key.** The state is `SAME`, `DIFFERENT` or `MISSING_ON_SOME`; the
   old left-only and right-only states have no meaning across more than two nodes. The class
   (`DRIFT`, `EXPECTED`, `UNCLASSIFIED`) is ADR-0043's classification unchanged, with its
   configuration class renamed to `DRIFT`. A state is also a word in the response, so no view
   carries it by colour alone.
4. **A node that cannot answer is left out, not fatal.** An unreadable node is listed with its
   classified failure reason and takes no part in any majority or state, so its absent keys never
   read as missing. The comparison is made when at least two nodes answered; with fewer, the
   response says no comparison could be made and gives each reason. This replaces ADR-0043 D5 for
   the case of one or more silent nodes among several.
5. **A passive backup contributes the keys it exposes.** A node that is not serving and exposes
   fewer attributes than a serving node is stated as having a reduced surface, and the attributes
   it does not expose are left out of its comparison rather than reported as missing on it.
6. **The summary counts drift and what was set aside.** It reports the drift keys, the nodes that
   differ on at least one of them, and the expected differences. Unclassified keys are listed
   but never counted.
7. **The view opens on drift.** It states in one sentence how many keys drift on how many nodes
   (or that none do, and how many expected differences it set aside), lists the drifting keys by
   default, and offers the expected differences and every key one switch away, a text search over
   keys and values, and a filter to the keys that differ on chosen nodes. The choices live in the
   URL. The MCP `config_diff` tool returns the same facts, drift keys only.

ADR-0043's other decisions stand: the pointer diff, the address settings keyed by `match`, the
allowlist that classifies instead of filtering, expected differences as their own class, the match
set from the queue snapshot cache capped at 25 with `#` always included and the cap disclosed,
read-only at the topology permission, and unaudited.

## Consequences

- The node that differs is named, whatever the cluster's size. A drift row reads "majority
  `ASYNCIO`, broker-3 differs: `NIO`", which is the sentence an operator wants.
- A tie has no majority, and the view says so instead of picking a node to blame. Two nodes that
  disagree are such a tie; a two-node cluster therefore reports every difference as a split.
- One silent node no longer hides the comparison, which makes a clean result over fewer nodes
  possible. The summary and the empty state therefore name the nodes that were not compared; a
  clean statement over a partial cluster would be a false one.
- The response carries every node's value for every key, so it grows with nodes times keys. It is
  bounded by the address-setting cap, and the MCP tool sends only the drifting keys.
- This breaks the endpoint and the MCP tool: `left`, `right`, `nodeA` and `nodeB` are gone, and so
  is the response shape. Nothing keeps the old parameters.
- The cost per node is unchanged, one batched request through the rate limiter, so a cluster of
  *n* nodes costs *n* requests per comparison.

## Alternatives considered

- **Keep the pair and add a baseline node.** Names the node that differs only from the chosen
  baseline, which may itself be the stray one.
- **Compare against the declaration only.** That is the drift report, and it needs a declaration.
  The two answer different questions and now say so, each linking to the other.
- **Name the stray node by a plurality when there is no majority.** Picks a culprit the data does
  not support; a tie is a finding, not a defect of the report.
- **Count a node that did not answer as disagreeing.** Turns a connection problem into a
  configuration finding, which is the failure mode ADR-0043 was written against.
