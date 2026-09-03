# ADR-0004: Topology by seed node plus auto-discovery

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi

## Context

A cluster has many nodes in primary/backup pairs. Enumerating them by hand is
tedious and drifts the moment the cluster changes. Pure auto-discovery has no
bootstrap.

## Decision

Register **one reachable seed node per cluster**. Call `listNetworkTopology()` to
learn every pair and its connectors; persist discovered nodes with
`discovered = true`. Re-discover on a schedule and on any notification implying a
topology change. Manual URL overrides (a node reachable only on a different
host/port than it advertises) are honoured and never overwritten by discovery
(`manual_override = true`).

*Amended by [ADR-0013](0013-seed-is-a-list.md): registration accepts a **list**
of seed URLs, not one. Phase 0 showed that discovered connectors are usually
unreachable internal hostnames, so one seed leaves most of the cluster
unmanageable; a list lets an operator register every node they can reach in one
call. Discovery, `NodeID` keying, and the override rule are unchanged.*

## Consequences

- One URL to set up a cluster; the model stays current on its own.
- Requires the seed to be reachable and management-enabled at registration time.
- Nodes that advertise internal addresses need a manual override; the schema and
  UI support it from day one.

## Alternatives considered

- **Static config of all nodes** — immediate drift, re-edited on every cluster
  change.
- **Auto-discovery only** — nothing to bootstrap from; also fails when the
  cluster connection is mid-outage.
