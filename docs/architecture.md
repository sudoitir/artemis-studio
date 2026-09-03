# Architecture

Artemis Studio is one stateless-ish Spring Boot service, a React SPA it serves,
and a PostgreSQL database. It talks to many Artemis clusters over their standard
management endpoints.

## Components and data flow

```
                    ┌─────────────────────────────────────────────┐
  Browser           │  React 19 SPA (served from the same jar)     │
  ──────────────────┤  Mantine 9 · TanStack Router/Query/Table     │
                    │  React Flow topology · Mantine charts        │
                    │  SSE client patches the Query cache          │
                    └───────────────┬─────────────────────────────┘
                          REST + SSE│  same origin, /api/v1
                    ┌───────────────▼─────────────────────────────┐
                    │  Spring Boot 4.1 · Java 25                   │
                    │                                              │
                    │  web/        controllers, DTOs, SSE hub       │
                    │  security/   local users, RBAC, audit filter  │
                    │  broker/     JolokiaBrokerClient ─┐           │
                    │              CoreEventClient  ────┤ (Phase 4)  │
                    │              CapabilityProbe      │           │
                    │  scheduler/  tiered scrape + per-node limiter │
                    │  domain/     topology, queues, messages, RR   │
                    │  alerting/   rule eval, channels             │
                    │  persist/    Spring Data JDBC + Liquibase     │
                    └──────┬──────────────────────────────┬────────┘
                           │                              │
                   ┌───────▼────────┐          ┌──────────▼──────────────┐
                   │  PostgreSQL    │          │  Artemis clusters       │
                   │  config, users │          │  Jolokia :8161 (pull)   │
                   │  audit, metrics│          │  Core :61616 (push+IO)  │
                   └────────────────┘          └─────────────────────────┘
```

### Scrape path (pull)

A tiered scheduler emits work per node:

| Tier | Content | Interval |
|---|---|---|
| A | topology, HA state (`Active`, `ReplicaSync`), broker counters | 5s |
| B | queues with consumers or non-zero depth | 15s |
| C | idle queues | 5m |

Each tick builds **one batched Jolokia POST per node** (an array of read
requests), so a node with 3,000 queues costs a handful of HTTP calls. A per-node
token bucket caps management calls/sec. Results upsert into `queue_snapshot`
(latest) and append to `metric_sample` (history, daily-partitioned).

### Event path (push, Phase 4+)

The Core client subscribes to `activemq.notifications` per cluster. Notifications
are normalised to domain events, fanned out to the SSE hub, and fed to the
request-reply correlator.

### Realtime to the browser

One SSE endpoint, `GET /api/v1/stream?clusterId=&topics=…`, multiplexes named
events. The client patches TanStack Query cache entries; components stay
declarative. Failure degrades to polling. See ADR-0003.

## State ownership

- **PostgreSQL** — clusters, nodes, credentials (AES-GCM), users, roles, audit,
  alert rules, request-reply expectations, and the metrics cache.
- **URL** — navigable UI state (selected cluster, filters, sort, pagination).
- **In-memory** — the SSE subscriber registry and the scrape scheduler's
  leadership. For multi-instance HA (post-MVP) the scheduler takes a Postgres
  advisory lock per cluster: one instance scrapes a cluster, every instance
  serves reads and SSE. The schema assumes this from day one.

## Broker transport and capabilities

See ADR-0002. A `CapabilityProbe` classifies a connection into
`MANAGEMENT_READ` / `MANAGEMENT_WRITE` / `NOTIFICATIONS` / `MESSAGE_IO`; the UI
gates features on the result and shows the `broker.xml` needed to unlock the rest.

HA: never trust config for who is live. `Active` is polled on every node; two
`true` in a pair → critical split-brain alert. Failover is followed, not
configured.

## Request-reply tracing

The flagship. Both patterns are handled by one correlator:

- **Shared reply queue + correlation id** — browse request and reply addresses,
  join on `JMSCorrelationID` / `_AMQ_CORRELATION_ID`, compute latency. No reply
  past the deadline → `TIMED_OUT`; reply with no request → `ORPHANED_REPLY`.
- **Temporary reply queues** — invisible to browsing; reconstructed from the
  notification lifecycle (`BINDING_ADDED` → `CONSUMER_CREATED` → … →
  `CONSUMER_CLOSED` / `BINDING_REMOVED`):

  | Observed | State |
  |---|---|
  | temp queue + consumer + request enqueued | `AWAITING_REPLY` |
  | reply before consumer close | `COMPLETED` (latency recorded) |
  | temp queue removed, request still unacked | `ORPHANED` (requester died) |
  | no reply, deadline passed, consumer still attached | `TIMED_OUT` |
  | request acked by responder, no reply produced | `RESPONDER_DROPPED` |

Deadlines come from `_AMQ_EXPIRE` / `JMSExpiration`, else a per-address
`rr_expectation`. Correlation is event-driven; payload capture is sampled and
bounded so tracing never becomes the load.

## Safety and audit

Every mutating endpoint accepts `?dryRun=true` and returns the affected count
without acting. Purge/delete require typed confirmation in the UI. Every mutation
writes an `audit_event` in the same transaction as the command — row created
before the broker call, updated with the outcome.

## Persistence notes

Liquibase (ADR-0008). Columns ordered by alignment to cut row padding;
per-table `autovacuum`/`fillfactor` on high-churn tables; `metric_sample`
range-partitioned with BRIN on `ts`. Server tuning in
`deploy/postgres/postgresql.tuning.conf`.
