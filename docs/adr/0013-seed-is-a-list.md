# ADR-0013: A cluster is registered from a list of seed URLs, not one

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi
- **Amends**: [ADR-0004](0004-topology-seed-and-autodiscovery.md) — "register one
  reachable seed node per cluster".

## Context

ADR-0004's stated benefit is *"One URL to set up a cluster; the model stays
current on its own."* Phase 0 then established, against the dev pair, that
`listNetworkTopology()` returns the **broker-to-broker `<connector>` host:port**
each node advertises — compose service names, Kubernetes pod DNS, internal VIPs —
and that *"this is the norm, not the exception."* A Jolokia management URL cannot
be derived from a Core connector.

So in the common containerised or NAT'd deployment, one seed URL yields exactly
one manageable node plus a list of discovered nodes the operator must add by hand,
one at a time. ADR-0004's promise is not kept where most deployments live.

## Decision

Registration accepts **`seedUrls: string[]`** — the set of Jolokia base URLs the
operator can actually reach. Studio probes each, unions the topology each
reports, and attaches every reachable seed URL to the node whose `NodeID` it
returns. Discovery still fills in nodes that no seed covers, marked
`discovered = true` with only their advertised connector and no management URL,
surfaced for the operator to complete.

Everything else in ADR-0004 stands: `NodeID` is the identity key, discovery runs
on a schedule and on demand, and `manual_override = true` rows are never
rewritten by discovery.

## Consequences

- The single-seed case is unchanged — a one-element list.
- An operator who can reach every node registers a fully manageable cluster in
  one call, which is what ADR-0004 wanted.
- The request body and the registration DTO carry a list; the frontend form
  accepts multiple URLs. Minor surface increase, no change to the discovery merge
  (already `NodeID`-keyed).
- "One URL to set up a cluster" becomes "the URLs you can reach" — still seed +
  discovery, not static enumeration of the whole cluster.

## Alternatives considered

- **Keep a single seed; PATCH each unreachable node afterward** (ADR-0004 as
  written). Works, but makes the common case a chore and leaves a
  half-manageable cluster until the operator grinds through the list. Rejected.
- **Auto-derive Jolokia URLs from Core connectors** (swap port, keep host). Wrong
  often enough to be dangerous — console and acceptor ports and hostnames are
  independent, and the host is frequently unreachable regardless. Rejected.
- **Discovery-only with a broadcast/multicast probe.** No bootstrap, and blocked
  by exactly the network boundaries that cause the problem. Rejected (also
  already rejected by ADR-0004).
