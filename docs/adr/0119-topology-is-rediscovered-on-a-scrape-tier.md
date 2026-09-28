# ADR-0119: Topology is rediscovered on a scrape tier, and the on-demand endpoint is removed

- **Status**: accepted
- **Date**: 2026-09-28
- **Deciders**: Mahdi Amirabdollahi

## Context

[ADR-0004](0004-topology-seed-and-autodiscovery.md) decided to "re-discover on a
schedule". Only the on-demand half was built. `POST /clusters/{id}/rediscover` sat behind
a **Check** button in the cluster header, on every view of the cluster. The scrape tiers
([ADR-0015](0015-tiered-scrape-scheduler.md)) refresh the state of nodes Studio already
knows about, but they never learn of a new one.

So a broker added to a running cluster did not appear until someone thought to press
**Check**. The button's label did not say that this was what it did.

## Decision

**Discovery runs as a fourth scrape tier, `scrape-discovery`,** at a
`scrape.discovery-interval` cadence that defaults to one minute. It is a runtime setting,
like tiers A to C ([ADR-0025](0025-live-scrape-cadence-scheduling-configurer.md)). Each
tick probes every manageable node of every cluster and reconciles the node set, exactly
as the on-demand path did. Manual overrides are still never overwritten. A new node
reaches open screens through the existing tier A `topology` signal.

It is a system operation. Like the other tiers' writes, it takes no permission check and
writes no audit event per tick. A cluster with no reachable node is skipped until the
next tick.

**The on-demand endpoint and its buttons are removed**: **Check** in the cluster header
and **Rediscover** on an empty topology. The empty topology now says that Studio keeps
looking on its own.

## Consequences

- A cluster's node set stays current with no operator action.
- Each manageable node gets one more batched Jolokia read (HA attributes and
  `listNetworkTopology()`) per interval. It goes through the per-node rate limiter
  ([ADR-0076](0076-rate-limit-every-jolokia-request-inside-the-client.md)).
- There is no way to force discovery sooner than the interval. An operator who needs
  that can shorten the interval in Settings.
- Past `REDISCOVER_CLUSTER` audit events stay readable. No new ones are written.

## Alternatives considered

- **Keep the button beside the schedule.** A control whose effect arrives on its own
  within a minute anyway invites the question of whether it did anything.
- **Rediscover inside tier A.** That runs at five seconds, which is far more often than
  membership changes. A separate cadence keeps the broker load proportionate.
