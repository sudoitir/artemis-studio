# ADR-0167: A broker is registered in one cluster, identified by its NodeID

- **Status**: accepted
- **Date**: 2026-10-01
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/15c-design-system`
- **Builds on**: [ADR-0013](0013-seed-is-a-list.md) (registration from a list of seeds)

## Context

Registration takes a list of seed URLs, probes them and persists every node the brokers report
(ADR-0013). Nothing checked whether those brokers were already registered, so an operator who pressed
Register twice, or registered the same brokers by another URL, got two clusters over one set of brokers.
Every view then showed the same queues twice, alerts fired twice, a replica scraped the brokers twice,
and a destructive operation could be run from either cluster without the other knowing.

A URL is a poor identity for a broker. The same broker is reached as `broker-1`, as its IP address, through
another port mapping, or with a trailing slash, and nodes found through topology discovery have no
management URL at all. The broker itself has a stable identity: its NodeID, written to its journal on first
start, read by every probe (`NodeID` in the HA read, `nodeID` in `listNetworkTopology()`), and already
stored on each node as `broker_node.artemis_node_id`. A live/backup pair shares one NodeID, so it also
identifies the pair as one broker.

A check in the service alone is not enough: two registrations of the same brokers running at once both
pass it before either commits.

## Decision

1. **Identity.** Two registrations are of the same brokers when any NodeID one of them reports (a seed's
   own, or one in its topology view) is carried by a node of the other. A partial overlap is the same
   brokers too. A seed whose broker reports no NodeID (a refused read) is compared by its management URL
   in normal form: scheme and host in lower case, the port explicit (the scheme's default when absent), no
   trailing slash, no query, fragment or user info. Host aliases are not resolved; the NodeID covers them.
2. **Check.** The connection check (`?dryRun=true`) and the registration both run it, after reading the
   seeds and before anything else, against every registered node's NodeID and management URL. The seeds
   are read once (`TopologyDiscovery.Survey`) for both the check and the topology.
3. **Database.** A new table `broker_identity (kind, identity, cluster_id)` with primary key
   `(kind, identity)` holds each registered cluster's NodeIDs, and the URL of any seed without one, and
   goes with its cluster. A registration claims its identities in one ordered statement with
   `ON CONFLICT DO NOTHING` and then looks for another holder. Postgres makes a claim wait for a conflicting
   claim that is not yet committed, so the second of two racing registrations finds the first's claim, and
   is refused like any other, never with a constraint violation. The table starts empty: clusters
   registered before it are still found by the check through `broker_node`.
5. **Claims follow the nodes.** A cluster claims what its nodes carry now: every NodeID, and the URL of a
   node without one. Every write that can change that rewrites the cluster's claims in its own
   transaction, releasing what it no longer has and claiming what it has in the same key order:
   discovery (at registration and on every discovery tick, so a node found later is claimed too), a
   tier-A read whose NodeID differs from the stored one, and a management URL override. Deleting a
   cluster deletes its claims.
6. **A stale claim is released, not obeyed.** When a registration's claim finds another holder but the
   check finds no node of any cluster carrying those identities, the holder's claim is stale (its node
   moved or its broker's journal was replaced while it could not be read). The registration releases it
   and claims again. A racing registration that has committed its nodes is still refused, because the
   check then finds them.
4. **Refusal.** A 409 problem of type `cluster-already-registered`, titled "These brokers are already
   registered", whose detail names the cluster and the overlapping nodes, with `existingClusterId`,
   `existingClusterName` and `overlappingNodes`. A caller with no read grant on that cluster is told only
   that the brokers are taken: naming the cluster would reveal that it exists (the authorization spec). The
   attempt is audited as a failure with the message the caller was given. The row belongs to no cluster,
   so no cluster's read grant guards it, and it must not name what its actor could not see; its seed URLs
   still let an administrator find the holder. Every refusal ends by saying that a cloned or restored
   broker carries the same NodeID and needs a fresh journal.

## Consequences

- One set of brokers is one cluster. The registration form names the cluster that has them and links to it.
- The NodeID is the broker's journal identity: a broker whose data directory is wiped gets a new one and
  can be registered again, which is right, since it is a new broker to Artemis too.
- A broker cloned from another (a copied VM, a restored backup of its data directory) carries the
  original's NodeID, and Studio cannot tell the two apart: registering the clone is refused, and there is
  no override. The refusal says so, and the fix is the broker's: give it a fresh journal so it gets its
  own NodeID. An override would reopen the duplicate clusters this decision exists to prevent.
- The discovery tick rewrites a cluster's claims each time it runs: one delete and one insert that write
  nothing when the nodes did not change.
- A test that needs several clusters over one broker can no longer register it several times; the
  replicas test copies a registered cluster in the database instead.
- The URL fallback does not see that two different URLs reach the same broker when neither reports a
  NodeID, which only happens when the management account cannot read the broker's attributes.

## Alternatives considered

- **A unique index on `broker_node.artemis_node_id`.** A pair's two nodes share a NodeID within one
  cluster, and a seed row and its discovered row can both carry it, so the index would refuse a correct
  cluster. Existing duplicate clusters would also fail the migration at boot.
- **Normalised URLs only.** Misses every alias the NodeID catches, and discovered nodes have no URL.
- **An advisory lock around registration.** Serialises the check without a constraint, but leaves the
  database with no rule of its own, and a lock key per broker is a NodeID claim in another form.
- **Warn, and register anyway.** Two clusters over one set of brokers is never what an operator wants;
  the duplicate views, alerts and scrapes cost more than the refusal.
