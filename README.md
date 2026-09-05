# Artemis Studio

> **⚠️ Work in progress — Artemis Studio is under active development and is not yet feature-complete. Expect breaking changes and incomplete functionality.**

**Cluster-wide management and observability for Apache ActiveMQ Artemis.**

The bundled Hawtio console manages one broker at a time and has no idea a cluster
exists. Artemis Studio is the other thing: one instance across many clusters —
live/backup topology, cross-node queues and addresses in a single table, safe
message operations, and first-class request-reply tracing.

It works against your **existing** brokers. No `broker.xml` rewrite beyond
enabling the management endpoints you almost certainly already run.

---

## Status

**Alpha.** Phases 0–2 are done: connect to a live/backup pair, see the whole
cluster in one place — the topology graph, every queue across every node in one
virtualized grid, addresses / consumers / sessions / connections / producers,
all updating live over SSE. Message operations — browse, send, move / retry / delete /
expire / purge, every mutation with `?dryRun=true` and a server-enforced bulk cap, a full
audit trail, and a DLQ view — landed in Phase 3 (message I/O is Jolokia-only until the
Core client in Phase 4).
The TODO list below is the plan, in order.

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

Every feature we intend to ship, grouped by phase. Context:
[`docs/roadmap.md`](docs/roadmap.md) and the ADRs. Feature phases go through OpenSpec.

### Phase 0 · Broker management spike

|  | Task |
|--|------|
| [x] | Boot dev compose primary/backup pair with replication; fix broker XML |
| [x] | Verify `listNetworkTopology()` shape (pairs, connectors) |
| [x] | Verify `listQueues(options, page, pageSize)` shape and paging |
| [x] | Verify `Active` / `ReplicaSync`; confirm failover and failback |
| [x] | Capture real `_AMQ_NotifType` values + headers from notifications |
| [x] | Batched Jolokia POST verified; note what needs the Core client |
| [x] | Write `docs/broker-management-notes.md` |

### Phase 1 · Connectivity and topology

|  | Task |
|--|------|
| [x] | `JolokiaBrokerClient` — read attrs, invoke ops, batched POST |
| [x] | `CapabilityProbe` — MANAGEMENT_READ/WRITE, NOTIFICATIONS, MESSAGE_IO |
| [x] | Credential vaulting (AES-GCM at rest), TLS to brokers |
| [x] | Register a cluster from a list of seed nodes ([ADR-0013](docs/adr/0013-seed-is-a-list.md)) |
| [x] | Topology auto-discovery + rediscovery; manual URL overrides kept |
| [x] | Live-node detection, replication state, corroborated split-brain ([ADR-0012](docs/adr/0012-corroborated-split-brain.md)) |
| [x] | `GET /clusters/{id}/{capabilities,topology,health}` |
| [x] | "Feature unavailable" UI with the `broker.xml` snippet to fix it |

### Phase 2 · Cross-node views + live UI

|  | Task |
|--|------|
| [x] | Tiered scrape scheduler (A/B/C) + per-node rate limiter |
| [x] | `queue_snapshot` upserts, cross-node aggregation |
| [x] | Queues view (routing type, depth, consumers, delivering, scheduled) |
| [x] | Addresses view |
| [x] | Consumers / sessions / connections / producers views |
| [x] | SSE hub (`GET /stream`) + polling fallback |
| [x] | React shell, routing, dark-first tokens |
| [x] | Topology graph (React Flow) — badges, replication, alert dots |
| [x] | Queue grid (TanStack Table) — virtualized, sort, filter |
| [x] | ⌘K command palette |

### Phase 3 · Message operations + audit

|  | Task |
|--|------|
| [x] | Browse messages; full headers, properties, body |
| [x] | Send message |
| [x] | Move / retry (DLQ replay) / delete by ids or filter |
| [x] | Purge queue with typed confirmation |
| [x] | `?dryRun=true` on every mutation → affected count |
| [x] | Bulk actions with a safety cap and preview |
| [x] | `audit_event` in the command transaction, updated with outcome |
| [x] | Audit log screen (filter by user, action, cluster, time) |
| [x] | DLQ management view |

### Phase 4 · Core client and push events

|  | Task |
|--|------|
| [x] | `CoreEventClient` (artemis-jakarta-client), live/backup aware |
| [x] | `activemq.notifications` consumer → normalized domain events |
| [x] | SSE fan-out of consumer/session/connection/binding events |
| [x] | `NOTIFICATIONS` capability gating with `broker.xml` hint |
| [x] | Faithful message I/O over Core when available |

### Phase 5 · Request-reply tracing (flagship)

|  | Task |
|--|------|
| [x] | Correlator + flow state machine |
| [x] | Shared-reply-queue pattern (correlation-id join, latency) |
| [x] | Temp-reply-queue pattern (lifecycle from notifications) |
| [x] | States: AWAITING_REPLY, COMPLETED, TIMED_OUT, ORPHANED, RESPONDER_DROPPED, ORPHANED_REPLY |
| [x] | `rr_expectation` config — addresses, deadlines, sampling |
| [x] | Deadlines from `_AMQ_EXPIRE`/`JMSExpiration` else expectation |
| [x] | `/clusters/{id}/rr/{flows,flows/{id},stats,expectations}` |
| [x] | Flows screen — in-flight list, per-address latency percentiles |
| [x] | "Stuck requests" panel |
| [x] | Bounded/sampled payload capture |

### Phase 6 · Metrics and charts

|  | Task |
|--|------|
| [x] | `metric_sample` writes from the scheduler |
| [x] | Daily partition create-ahead + retention drop job |
| [x] | `GET /clusters/{id}/metrics` (subject, metric, range, step) |
| [x] | Built-in charts — depth, throughput, consumers, RR latency |
| [ ] | Rollup tables — only when a query is measurably slow |
| [x] | Frictionless cluster registration — example topology cards, live discovered-topology preview |
| [x] | Advanced collapsible sidebar — icon rail, tooltips, persisted state |

### Phase 7 · Alerting

|  | Task |
|--|------|
| [x] | Rule model + evaluation loop (`for` duration) |
| [x] | `alert_state` OK → PENDING → FIRING → resolved |
| [x] | Notification channels — webhook, Slack |
| [x] | Built-in critical alerts — split-brain, node down, replication desync |
| [x] | Rule CRUD + alerts screen |

### Phase 8 · Governance

|  | Task |
|--|------|
| [ ] | Local users in Postgres; first-run admin bootstrap |
| [ ] | Roles → permissions; scoped (global / environment / cluster) |
| [ ] | Read-only mode enforced on every mutating path |
| [ ] | Per-environment cluster grouping |
| [ ] | OIDC / SSO login, claim → role mapping |
| [ ] | Sessions, login/logout, `GET /me` |

### v1.0 · Hardening and reach

|  | Task |
|--|------|
| [ ] | Multi-instance HA — Postgres advisory lock per cluster |
| [ ] | Helm chart |
| [ ] | Docs site |
| [ ] | Slow-consumer detection |
| [ ] | Message replay from a captured payload |
| [ ] | Payload inspection helpers (pretty-print, type detection) |

### Beyond

|  | Task |
|--|------|
| [ ] | ArkMQ operator integration (read broker CRs to auto-register) |
| [ ] | JMX transport |
| [ ] | Saved / shareable views |
| [ ] | Scheduled reports |
| [ ] | Broker config diff across a pair |
| [ ] | Prometheus scrape ingestion option |
| [ ] | Artemis MCP |


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
