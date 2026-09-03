## Why

Phase 0 verified, against a real broker pair, exactly how Artemis exposes HA
state, topology, and queues over Jolokia (`docs/broker-management-notes.md`).
Nothing in the product consumes it yet: the backend is a bare Spring Boot
skeleton with no controllers, and the schema tables for clusters, nodes, and
credentials sit empty. Phase 1 builds the first real product layer on top of
that spike — the thing an operator points at a broker to learn, in one view,
who is live, whether replication is healthy, and what this connection is and is
not allowed to do.

## What Changes

- **New `JolokiaBrokerClient`** — batched reads and operation invocations, one
  HTTP POST per node per scrape, with per-entry error isolation and the
  double-JSON-encoding of `listNetworkTopology()` / `listQueues()` handled.
- **New `CapabilityProbe`** — classifies a connection into `MANAGEMENT_READ`,
  `MANAGEMENT_WRITE`, `NOTIFICATIONS`, `MESSAGE_IO`, each with a status
  (`AVAILABLE` / `UNAVAILABLE` / `UNKNOWN`), a human reason, and where relevant
  the exact `broker.xml` snippet that unlocks it. `MANAGEMENT_WRITE` is
  **inferred** from a harmless read-only exec — never a real mutation.
- **New secret vault** — broker credentials encrypted at rest with
  `AES/GCM/NoPadding`, root key from `ARTEMIS_STUDIO_SECRET_KEY`, AAD bound to
  `cluster_id || kind`. Broker TLS via Spring Boot SSL bundles.
- **Cluster registration from one or more seed URLs** — `POST /api/v1/clusters`
  takes `seedUrls: string[]`, probes each, discovers the rest of the cluster,
  and persists. `?dryRun=true` returns capabilities and topology **without
  persisting**.
- **Topology auto-discovery and rediscovery** — `listNetworkTopology()` maps
  every pair and its connectors; discovered nodes are keyed by `NodeID`; manual
  URL overrides are honoured and never overwritten by discovery.
- **Live-node detection, replication state, split-brain flag** — a minimal
  `@Scheduled` refresh reads `Active`/`Started`/`Backup`/`ReplicaSync` per node.
  Split-brain requires **corroborated** evidence (two nodes active, same NodeID,
  same refresh cycle, confirmed on the next) so planned failover does not false-alarm.
- **New read API** — `GET /api/v1/clusters/{id}/capabilities`, `/topology`,
  `/health`, plus `POST /clusters/{id}/rediscover` and
  `PATCH /clusters/{id}/nodes/{nodeId}` for the override.
- **New frontend cluster view** — replaces the placeholder card: register form,
  the identity-axis pair rendering, the capability ledger with copyable
  `broker.xml`, and the "found, not yet manageable" flow for internal-hostname nodes.
- **BREAKING (internal only, pre-release):** `spring-boot-starter-webflux` is
  removed from `pom.xml`; the unused `artemis-studio.jolokia.origin-header`
  config key is deleted.
- **New schema changeset** `008-node-ha-state.sql` — adds `observed_cycle`,
  `active`, `replica_sync` to `broker_node`. Released changesets 001–007 untouched.
- **Five new ADRs** — 0009 (secret vaulting + TLS), 0010 (Jolokia over blocking
  `RestClient`; webflux removed), 0011 (`JdbcClient` over Spring Data JDBC),
  0012 (corroborated split-brain — amends ADR-0002), 0013 (seed list — amends
  ADR-0004). ADR-0002 and ADR-0004 get their affected bullets superseded with backlinks.

## Capabilities

### New Capabilities

- `broker-connectivity`: how Studio reaches a broker over Jolokia — seed URL
  input, batched request/response contract, credential vaulting, TLS, and the
  connection error taxonomy.
- `broker-capabilities`: the capability probe — the four capability classes,
  how each is determined, the three-state status model, and the `broker.xml`
  hint contract.
- `cluster-registration`: registering, listing, inspecting, and removing a
  cluster; the `?dryRun` contract; audit of every mutation.
- `cluster-topology`: topology discovery and rediscovery, `NodeID` as the pair
  key, manual URL overrides, live-node / replication-state / split-brain
  derivation, and the `/topology` and `/health` responses.

### Modified Capabilities

None — `openspec/specs/` is currently empty; all four capabilities are new.

## Impact

- **Code**: new `broker/`, `security/`, `domain/topology/`, `persist/`,
  `scheduler/`, `web/` packages under
  `io.github.sudoitir.artemisstudio`; `config/SecurityConfig.java` gains a
  startup warning; `web/src/App.tsx`, `web/src/theme.css`, and a new
  `web/src/clusters/` + `web/src/api/` on the frontend.
- **APIs**: introduces `/api/v1/clusters**` — the first product HTTP surface.
- **Dependencies**: removes `spring-boot-starter-webflux`. No additions —
  `RestClient`, `JdbcClient`, `SslBundles`, and JDK AES-GCM are all already present.
- **Config**: `ARTEMIS_STUDIO_SECRET_KEY` must be valid base64 of 32 bytes or
  the app fails to start; the dev compose value is replaced and the dev port
  binds to loopback.
- **Schema**: one additive changeset on `broker_node`.
- **Docs**: five ADRs added; ADR-0002/0004 annotated; `README.md` Phase 1 rows
  ticked; `openspec/project.md` current-phase line updated.
