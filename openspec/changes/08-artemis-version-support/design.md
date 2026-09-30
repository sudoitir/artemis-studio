## Context

Studio already reads each node's `Version` attribute (topology discovery and the tier-A scrape both read it
with the HA attributes) and stores it in `broker_node.version`; nothing parsed or compared it. Capabilities
are probed once per cluster on one manageable node, and every cluster-wide write fans out per node through
`BrokerCommands` with a per-node `LifecycleOutcome`. The integration suite ran against one image,
`apache/activemq-artemis:2.44.0`, while the client is 2.57.0. Artemis became its own Apache project: 2.50
and later ship as `apache/artemis`, and the old image line ends at 2.44.0.

## Goals / Non-Goals

**Goals:**
- One place that states the supported range, used by registration, the UI, CI and the docs.
- Per-node gating of the few operations newer than the minimum, enforced on the server so the UI, the API
  and MCP agree.

**Non-Goals:**
- Probing capabilities per node. The version gate is computed from recorded versions; the existing probe
  stays per cluster.

## Decisions

### D1. The range: 2.33.0 to 2.57.0, from the management API's history
The minimum is the first release with every operation Studio's core features call. Reading
`ActiveMQServerControl` at each release tag gave 2.32.0: the JSON `addAddressSettings(address, json)`, which
capture, plugin taps, staging queues and configuration edits use, first appears there. Everything else Studio
calls (JSON `createQueue`/`updateQueue`/`createBridge`, `updateAddress`, paged `browse`,
`listNetworkTopology`, `getRolesAsJSON`) is present in 2.30, except the JSON `createDivert(json)`, which
appears in 2.38.0 (D4). The full integration suite run against 2.32.0 failed on divert creation (routing,
capture, plugin taps, configuration apply), on a test helper whose Jolokia client did not accept the
`text/plain` content type older agents send (Studio's own clients already did), and, once those were fixed,
on the thirteen-argument `addSecuritySettings` that carries the view and edit permissions. That form arrived
in 2.33.0, and the eleven-argument one cannot carry those permissions, so the minimum is 2.33.0. The
latest tested is the newest release, 2.57.0, which is also the client version.
`BrokerVersion.MINIMUM` / `LATEST_TESTED` hold both.

*Alternatives:* 2.38.0 (no gates, fewer operators served); 2.44.0 (what was already tested, shuts out a
year of releases). The user chose the widest range that works.

### D2. Registration checks the seeds' versions before anything is stored
`connectAll` reads `Version` from each reachable seed (one attribute read). A seed below the minimum fails
the check and the registration with a new `BrokerConnectionException.Kind.UNSUPPORTED_VERSION` (422)
naming the minimum and the seed's version, audited like any other failed registration. Dry run and real
run share the path, so they give the same verdict and the dry run stores nothing. A release newer than
tested is accepted; the preview's topology carries `versionSupport` per node and the registration screen
warns. Topology-only nodes have no Jolokia URL and so no version; they are `UNKNOWN`, never refused.

### D3. Support status and gates are computed on read
`NodeEndpointView.versionSupport` and `CapabilitiesView.versionGates` are derived from the stored version
each time a view is built. An upgrade picked up by the tier-A scrape, or a node added by rediscovery, is
reflected with no new write path; a later node below the minimum is shown with a warning and the cluster
stays registered.

### D4. Divert creation falls back; `VersionGate` is for what cannot
`DivertOperations.createDivert` sends the JSON form and, on the broker's "No operation
createDivert(java.lang.String)" error, retries with the positional
`createDivert(name, routingName, address, forwardingAddress, exclusive, filter, transformer, routingType)`,
which every supported release has and which carries every field Studio sends (an absent filter and
transformer go as empty strings, which the broker reads as none). Every divert path routes through it, so
capture, plugin taps, the configuration apply and routing work across the range. The user chose this over
gating those features below 2.38 or raising the minimum to 2.38.

An operation that cannot fall back becomes a `VersionGate`: a feature id, a label and the first release
that has it; `VersionGate.ALL` is empty today. `assess(nodes)` gives a
`CapabilityView`-shaped result over the active nodes: AVAILABLE when every node with a known version has
it (or some do: a mixed cluster keeps the operation and the reason names the nodes it will skip),
UNAVAILABLE when none does, UNKNOWN when no node has reported a version. The capability ledger lists each
gate with the release it needs, and a control gates on it with the existing `gateFor`. On the server,
`BrokerCommands.Command.requires` skips a node whose recorded version is older, with a new
`NodeStatus.UNSUPPORTED_VERSION` (not a failure; counts toward a partial outcome). The gate is a record
rather than an enum so an empty list produces no empty enum in the API. `BrokerCommands.Command` is
`@PluginApi`; its new component removes the old canonical constructor, which japicmp reports as a break, so
`Contract.VERSION` goes from 5 to 6 (the user chose this over deferring enforcement) and the plugin template
follows.

### D5. CI runs the suite at both ends
The backend job's matrix gains `artemis: [apache/activemq-artemis:2.33.0, apache/artemis:2.57.0]` across
the three shards. The integration base reads the image from `-Dartemis.image`, defaulting to the latest.
`BrokerVersionTest` reads `ci.yml` and fails when the matrix and the constants disagree. Dev and demo
compose files move to `apache/artemis:2.57.0`.

## Risks / Trade-offs

- [Six backend runners instead of three] → The suite was already sharded; the extra runners run in
  parallel and add no wall time.
- [An operation newer than the minimum added later] → The minimum run in CI fails it before it merges;
  it then gets a fallback or a `VersionGate`.
- [The positional `createDivert` is deprecated for removal] → Only a broker without the JSON form gets
  it; the fallback goes when the minimum reaches 2.38.
