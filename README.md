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

The development plan. Each item is scoped to stand on its own — goal, files in
scope, and a concrete "done when". Full context in
[`docs/roadmap.md`](docs/roadmap.md) and the ADRs.

### 0 · Broker management spike

- **Goal**: prove the Artemis management surface this whole design rests on.
  Boot the dev compose primary/backup pair; from a scratch Java main or test,
  call `listNetworkTopology()`, `listQueues(filter, page, pageSize)`, read
  `Active` and `ReplicaSync`, and subscribe to `activemq.notifications` to
  capture real `_AMQ_NotifType` values. Record exact signatures and payload
  shapes.
- **In scope**: `deploy/compose/compose.dev.yaml`,
  `deploy/compose/artemis/**/broker.xml`, a throwaway `spike/` or a
  `@Disabled` integration test. No production code.
- **Done when**: the pair boots, backup reports `Active=false` while primary
  reports `true`, killing the primary triggers failover, and a short markdown
  note in `docs/` lists the verified call signatures. Fix the broker XML as
  needed.

### 1 · Jolokia client, capability probe, cluster registration, topology

- **Goal**: register a cluster from one seed node; probe capabilities; discover
  the rest of the topology; detect who is live and flag split-brain.
- **In scope**: `broker/` (JolokiaBrokerClient, CapabilityProbe,
  TopologyDiscovery), `domain/topology`, `web/` controllers for
  `/api/v1/clusters`, `/capabilities`, `/topology`, `/discover`; Liquibase
  changes only if a column is missing.
- **Done when**: `POST /api/v1/clusters` with a seed URL persists the cluster,
  discovers all nodes, and `GET /topology` returns pairs + replication state +
  a split-brain flag; capabilities reflect what the connection actually allows;
  integration test against the dev pair.

### 2 · Cross-node resource views, scrape scheduler, SSE, UI shell

- **Goal**: the "whole cluster in one table" view, kept live.
- **In scope**: `scheduler/` (tiered work, per-node token bucket, batched
  Jolokia POST), `persist/` (`queue_snapshot` upserts), `web/` SSE hub +
  `/api/v1/clusters/{id}/queues|addresses`, `web/src` routes, the topology
  graph (React Flow), the queue grid (TanStack Table + Mantine).
- **Done when**: queues and addresses across every node render in one sortable,
  filterable grid; counters update over SSE; the topology graph shows
  live/backup badges and replication state; brokers see batched, rate-limited
  calls only.

### 3 · Message operations + audit

- **Goal**: browse, send, move, retry, delete — safely.
- **In scope**: `domain/messages`, `web/` message endpoints with `?dryRun`,
  `security/` audit filter + `audit_event` write in the command transaction,
  `web/src` message browser + typed-confirmation dialogs, audit log screen.
- **Done when**: every mutation has a working dry-run returning an affected
  count; purge/delete demand typed confirmation; every action writes an audit
  row with outcome; the audit screen filters by user, action, cluster, time.

### 4 · Core client and notifications

- **Goal**: the push channel.
- **In scope**: `broker/CoreEventClient` (artemis-jakarta-client), a
  per-cluster `activemq.notifications` consumer, normalisation to domain
  events, SSE fan-out, `NOTIFICATIONS` capability wiring.
- **Done when**: consumer/session/connection/binding events from the dev pair
  appear live in the UI; the capability turns on only when the Core acceptor is
  reachable and `consume` on `activemq.notifications` is granted, with the
  `broker.xml` hint shown when it is not.

### 5 · Request-reply tracing

- **Goal**: the flagship. Trace request-reply end to end for both the
  shared-reply-queue and temporary-reply-queue patterns; surface stuck and
  orphaned requests.
- **In scope**: `domain/requestreply` (correlator, state machine per
  `docs/architecture.md`), `rr_expectation` / `rr_flow` / `rr_event`
  persistence, `/api/v1/clusters/{id}/rr/*` endpoints, a "flows" screen with a
  per-address latency panel and a "stuck requests" list.
- **Done when**: a demo request-reply app run against the dev pair shows
  `COMPLETED` flows with latency, a killed requester shows `ORPHANED`, and a
  responder that acks without replying shows `RESPONDER_DROPPED`; correlation is
  driven by the event stream, and payload capture is sampled/bounded.

### 6 · Metrics and charts

- **Goal**: history.
- **In scope**: `metric_sample` writes from the scheduler, a daily
  partition-create + retention job, `/api/v1/clusters/{id}/metrics`, Mantine
  charts dashboards.
- **Done when**: queue depth / throughput / consumer count / RR latency chart
  over selectable ranges; partitions are created ahead and dropped past
  retention; a slow dashboard query is the trigger to add rollups, nothing
  sooner.

### 7 · Alerting

- **Goal**: tell someone before the operator has to look.
- **In scope**: `alerting/` (rule eval loop over `metric_sample`,
  `alert_state`), `notification_channel` delivery (webhook, Slack), rule CRUD +
  alerts screen.
- **Done when**: a threshold rule with a `for` duration transitions
  OK → PENDING → FIRING, delivers to a channel once, and resolves; split-brain
  raises a built-in critical alert.

### 8 · RBAC and SSO

- **Goal**: real governance.
- **In scope**: `security/` local users → role/permission/scoped assignment,
  per-environment grouping, read-only mode enforcement on every mutating path,
  OIDC login.
- **Done when**: a VIEWER cannot mutate anything, an OPERATOR scoped to one
  environment cannot touch another, read-only clusters reject writes with a
  clear error, and OIDC login maps claims to roles.

### v1.0 · Hardening and reach

- Multi-instance HA via Postgres advisory locks (one scraper per cluster, all
  instances serve reads); Helm chart; docs site; slow-consumer detection;
  message replay from a captured payload.

### Beyond

- ArkMQ operator integration (read broker CRs to auto-register clusters); JMX
  transport; saved views; scheduled reports; broker config diff across a pair.

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
