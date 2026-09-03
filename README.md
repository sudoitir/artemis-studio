# Artemis Studio

**Cluster-wide management and observability for Apache ActiveMQ Artemis.**

The bundled Hawtio console manages one broker at a time and has no idea a cluster
exists. Artemis Studio is the other thing: one instance across many clusters —
live/backup topology, cross-node queues and addresses in a single table, safe
message operations, and first-class request-reply tracing. Think "Lenses for
Kafka", for Artemis.

It works against your **existing** brokers. No `broker.xml` rewrite beyond
enabling the management endpoints you almost certainly already run.

> _Screenshot goes here once Phase 2 renders the topology graph._

---

## Status

**Pre-alpha — workspace scaffold.** The build is green and the app starts; there
are no product features yet. The TODO list below is the plan, in order.

## Quick start (dev)

```bash
just up          # Postgres + a real Artemis primary/backup pair + Artemis Studio
open http://localhost:8080
```

`just` on its own lists every task, grouped. Without `just`:

```bash
docker compose -f deploy/compose/compose.dev.yaml up --build -d
```

## Build and test

```bash
just verify          # backend (Liquibase vs Testcontainers Postgres, tests) + frontend build + lint
./mvnw -Pfrontend package   # single jar with the SPA baked in
```

Needs JDK 25, Node 22, Docker. A dev container is provided
(`.devcontainer/`, Ubuntu 26.04 LTS).

## Stack

| Layer | Choice | Why |
|---|---|---|
| Backend | Java 25, Spring Boot 4.1.0, Maven | — |
| Database | PostgreSQL, Liquibase migrations | [ADR-0008](docs/adr/0008-schema-migrations-liquibase.md) |
| Frontend | React 19 + Vite + Mantine 9, TanStack Router/Query/Table, React Flow | [ADR-0005](docs/adr/0005-frontend-stack-mantine-over-shadcn-and-mui.md) |
| Broker transport | Jolokia HTTP first, Artemis Core client second, capability-gated | [ADR-0002](docs/adr/0002-broker-transport-and-capability-model.md) |
| Realtime | SSE | [ADR-0003](docs/adr/0003-realtime-via-sse.md) |
| Packaging | one container image, Docker Compose first | [ADR-0007](docs/adr/0007-packaging-single-image-compose-first.md) |

Architecture: [`docs/architecture.md`](docs/architecture.md). All decisions:
[`docs/adr/`](docs/adr/).

## How work happens

Every feature goes through **OpenSpec** (`/opsx:propose` → `apply` → `archive`).
Significant decisions get an **ADR**. Library facts come from `ctx7`, not memory.
See [`CLAUDE.md`](CLAUDE.md) and [`.claude/rules/`](.claude/rules/).

---

## TODO

Every feature we intend to ship, grouped by phase. Each phase is a focused unit
of work; the sub-tasks are the checklist. Context: [`docs/roadmap.md`](docs/roadmap.md)
and the ADRs. Feature phases go through OpenSpec.

### Phase 0 · Broker management spike

- [ ] Boot the dev compose primary/backup pair with replication working; fix
      `deploy/compose/artemis/**/broker.xml`
- [ ] Verify `listNetworkTopology()` shape (pairs, connectors)
- [ ] Verify `listQueues(filter, page, pageSize)` shape, paging, attributes
- [ ] Verify `Active` / `ReplicaSync` reads; confirm failover and failback
- [ ] Capture real `_AMQ_NotifType` values + headers from `activemq.notifications`
- [ ] Batched Jolokia POST verified; note what needs the Core client
- [ ] `docs/broker-management-notes.md` with verified signatures and payloads

### Phase 1 · Connectivity and topology

- [ ] `JolokiaBrokerClient` — read attributes, invoke ops, batched POST
- [ ] `CapabilityProbe` → `MANAGEMENT_READ/WRITE`, `NOTIFICATIONS`, `MESSAGE_IO`
- [ ] Credential vaulting (AES-GCM at rest), TLS to brokers
- [ ] Register a cluster from one seed node
- [ ] Topology auto-discovery + rediscovery; manual URL overrides preserved
- [ ] Live-node detection (`Active`), replication state, split-brain flag
- [ ] `GET /api/v1/clusters/{id}/{capabilities,topology,health}`
- [ ] "Feature unavailable" UI with the exact `broker.xml` snippet to enable it

### Phase 2 · Cross-node resource views + live UI

- [ ] Tiered scrape scheduler (A/B/C) with per-node token-bucket rate limit
- [ ] `queue_snapshot` upserts; cross-node aggregation
- [ ] Queues view — anycast/multicast, depth, consumers, delivering, scheduled
- [ ] Addresses view
- [ ] Consumers / sessions / connections / producers views
- [ ] SSE hub (`GET /api/v1/stream`); polling fallback
- [ ] React shell, routing, dark-first tokens wired
- [ ] Topology graph (React Flow) — live/backup badges, replication, alert dots
- [ ] Queue grid (TanStack Table + Mantine) — virtualized, sort, filter
- [ ] ⌘K command palette (jump to cluster / queue / action)

### Phase 3 · Message operations + audit

- [ ] Browse messages (filter, paged); full headers, properties, body view
- [ ] Send message
- [ ] Move / retry (DLQ replay) / delete — by ids or filter
- [ ] Purge queue with typed confirmation
- [ ] `?dryRun=true` on every mutation → affected count, no action
- [ ] Bulk actions with a safety cap and preview
- [ ] `audit_event` written in the command transaction, updated with outcome
- [ ] Audit log screen — filter by user, action, cluster, time
- [ ] DLQ management view (what's dead, where it came from, redelivery count)

### Phase 4 · Core client and push events

- [ ] `CoreEventClient` (artemis-jakarta-client), live/backup aware
- [ ] Per-cluster `activemq.notifications` consumer → normalized domain events
- [ ] SSE fan-out of consumer/session/connection/binding events
- [ ] `NOTIFICATIONS` capability gating with `broker.xml` hint
- [ ] Faithful message I/O over Core (real headers/properties) when available

### Phase 5 · Request-reply tracing (flagship)

- [ ] Correlator + flow state machine (per `docs/architecture.md`)
- [ ] Shared-reply-queue pattern — correlation-id join, latency
- [ ] Temp-reply-queue pattern — lifecycle reconstruction from notifications
- [ ] States: `AWAITING_REPLY`, `COMPLETED`, `TIMED_OUT`, `ORPHANED`,
      `RESPONDER_DROPPED`, `ORPHANED_REPLY`
- [ ] `rr_expectation` config — which addresses to trace, deadlines, sampling
- [ ] Deadlines from `_AMQ_EXPIRE`/`JMSExpiration`, else per-address expectation
- [ ] `/api/v1/clusters/{id}/rr/{flows,flows/{id},stats,expectations}`
- [ ] Flows screen — in-flight list, per-address latency histogram/percentiles
- [ ] "Stuck requests" panel
- [ ] Bounded/sampled payload capture

### Phase 6 · Metrics and charts

- [ ] `metric_sample` writes from the scheduler
- [ ] Daily partition create-ahead + retention drop job
- [ ] `GET /api/v1/clusters/{id}/metrics` (subject, metric, range, step)
- [ ] Built-in charts — queue depth, throughput, consumers, RR latency
- [ ] Rollup tables — only when a dashboard query is measurably slow

### Phase 7 · Alerting

- [ ] Rule model + evaluation loop over `metric_sample` (`for` duration)
- [ ] `alert_state` OK → PENDING → FIRING → resolved
- [ ] Notification channels — webhook, Slack (email later)
- [ ] Built-in critical alerts — split-brain, node down, replication desync
- [ ] Rule CRUD + alerts screen

### Phase 8 · Governance

- [ ] Local users in Postgres; first-run admin bootstrap
- [ ] Roles → permissions; scoped assignment (global / environment / cluster)
- [ ] Read-only mode enforced on every mutating path
- [ ] Per-environment cluster grouping
- [ ] OIDC / SSO login, claim → role mapping
- [ ] Session handling, login/logout, `GET /api/v1/me`

### v1.0 · Hardening and reach

- [ ] Multi-instance HA — Postgres advisory lock per cluster (one scraper, all
      serve reads)
- [ ] Helm chart
- [ ] Docs site
- [ ] Slow-consumer detection
- [ ] Message replay from a captured payload
- [ ] Message payload inspection helpers (pretty-print, type detection)

### Beyond

- [ ] ArkMQ operator integration — read broker CRs to auto-register clusters
- [ ] JMX transport
- [ ] Saved views / shareable filters
- [ ] Scheduled reports
- [ ] Broker config diff across a pair
- [ ] Prometheus scrape ingestion option

---

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Issues and PRs welcome once Phase 1
lands something to build on.

## Licence

[Apache-2.0](LICENSE). Same licence as Artemis itself.

Apache ActiveMQ and Apache ActiveMQ Artemis are trademarks of the Apache Software
Foundation. Artemis Studio is an independent project and is not produced by,
endorsed by, or affiliated with the Apache Software Foundation. References to
"Artemis" describe the broker this tool manages.
