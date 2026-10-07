# ADR-0175: Management URLs are derived from a pattern and proved by NodeID

- **Status**: accepted
- **Date**: 2026-10-07
- **Deciders**: Mahdi Amirabdollahi
- **Amends**: [ADR-0013](0013-seed-is-a-list.md) (a cluster is registered from a list of seeds),
  [ADR-0004](0004-topology-seed-and-autodiscovery.md) (a discovered node waits for an operator to give it a URL)
- **Builds on**: [ADR-0167](0167-a-broker-is-registered-in-one-cluster-by-its-node-id.md) (NodeID identifies a broker)

## Context

Artemis advertises only Core connectors: `listNetworkTopology()` names the host and Core port of every
node, never where its Jolokia agent is. ADR-0013 therefore asked the operator for every management URL
they could reach, and a node nobody listed stayed "found, not yet manageable" until someone typed its
URL by hand (`AddManagementUrl`). For a cluster of two HA pairs that is four URLs at registration and a
chore after every new broker, although in practice every broker of a cluster serves management on the
same scheme, port and path.

A guessed address is only safe if it is checked. Behind a load balancer, or on a recycled address, the
host Studio derived may answer for another broker, and managing that one would write to the wrong
cluster.

## Decision

1. **One seed is enough.** A cluster stores a **management URL pattern**, `scheme://{host}:port/path`.
   Registration defaults it to the first seed with its host replaced by `{host}`; the operator may edit
   it (under *Advanced*, and later in the cluster's Connection settings).
2. **A node's URL is derived and proved.** For every discovered node, discovery puts the host of the
   node's connector into the pattern and asks that address, with the management account, for the HA
   attributes in one batched read through the rate-limited `JolokiaBrokerClient`. The URL is attached
   only when the broker answering reports the node's **NodeID**. Otherwise the node stays known and
   unmanaged, with a classified reason (`url_problem`: no pattern, unreachable, credentials rejected,
   not a management endpoint, TLS failed, another broker answered).
3. **Each URL records where it came from** (`url_source`): `SEED` (the operator gave it), `DERIVED`
   (the pattern and a NodeID proof), `MANUAL` (set on the node). Rediscovery re-derives and re-proves
   `DERIVED` URLs when the pattern changed or the node has none, never asks again about a URL that is
   already what the pattern derives, and never touches a `SEED` or `MANUAL` one. The Core URL has its
   own manual flag, because the source describes the management URL only.
4. **A seed host with several addresses fans out.** `InetAddress.getAllByName` expands a seed into one
   seed per address, keeping scheme, port and path, as a `bootstrap.servers` list is. A name with one
   address of each family is one dual-stack host and is not expanded. Two addresses answering as the
   same NodeID in the same role are one broker, whose URL is the first that answered. A TLS seed keeps
   its host name, which the handshake and the certificate check need; its addresses are read only to
   learn NodeIDs and never become a node's URL.
5. **The two accounts are tried separately.** A registration check, and the check of an edited
   connection, report for every node whether the management account and the Core account (one Core
   session opened and closed) were accepted, rejected, unreachable or not tried. A refusal of either
   is the connection failure class `CREDENTIALS_REJECTED`, naming the account, and cluster health says
   which account was rejected on which nodes instead of calling the brokers unreachable.

## Consequences

- A cluster of any size registers from one URL, and a broker that joins is managed within one discovery
  interval when it serves management on the pattern.
- A deployment whose brokers serve management on different ports or paths still needs a manual URL for
  the odd ones; the pattern is one per cluster, not one per node.
- Discovery makes one more batched read per unproved node per tick, within the per-node rate limit.
- `broker_node.discovered` and `manual_override` are replaced by `url_source`, `url_problem` and
  `core_url_manual`; the API's `discovered` and `manualOverride` go with them. There is no migration of
  existing rows (no compatibility until the first stable release): a node keeps its URL, and the next
  discovery records the source of every URL it derives.
- The broker connection failure `UNAUTHORIZED` is renamed `CREDENTIALS_REJECTED` and carries the account.

## Alternatives considered

- **Attach the derived URL without proof.** Simpler and one read cheaper, and wrong behind a load
  balancer. Rejected: the NodeID check is what makes a guess safe.
- **Jolokia discovery (multicast).** Off by default in Artemis, and not routable across networks.
- **A pattern per node, or a list of URLs.** Today's pain, with a new name.
- **SRV records.** Not offered by Artemis deployments in practice.
