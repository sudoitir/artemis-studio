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
                    │  domain/     topology, queues, messages, RR,  │
                    │              alerting conditions + state      │
                    │  service/    orchestration incl. alert eval   │
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

A tiered scheduler emits work per node (ADR-0015). Cadences are runtime-tunable
via `studio_setting`; the values below are the defaults.

| Tier | Content | Interval |
|---|---|---|
| A | HA state (`Active`, `Started`, `ReplicaSync`, …) + per-cluster split-brain corroboration | 5s |
| B | the first `listQueues` page per node — a fast refresh of the busiest queues | 15s |
| C | one `listQueues` page per node per tick, walking the whole set, then reaping removed queues | 5m |

Each tick is **one Jolokia POST per node** (the resolved broker MBean name is
cached process-wide, so a tick no longer pays an extra `search`). Artemis 2.44's
`sortColumn` / `GREATER_THAN` options both 500 with an NPE
(`docs/broker-management-notes.md` §10), so there is no broker-sorted "hot page":
tier B is best-effort speed on page 1, tier C is the coverage guarantee. A
per-node permit bucket (`NodeScrapeLimiter`) caps management calls/sec. Network
I/O runs on virtual threads, one slow node never blocks its siblings, and it
never runs inside a DB transaction — each node's result is handed to a short
`@Transactional` persist step. Queue rows upsert into `queue_snapshot` via a
JDBC `INSERT … ON CONFLICT` batch (ADR-0016, a scoped exception to ADR-0011);
metric points append to `metric_sample` and a nightly reaper trims past the
retention window (daily partitioning is still Phase 6).

### Cross-node aggregation (read)

A primary and its synced backup share one `NodeID` and are **one logical node**
(ADR-0017). The queue grid is built from `queue_snapshot`, grouped by
`(address, queueName, routingType)`: each row carries a per-node cell, rolled-up
cluster totals, and `nodesPresent / nodesTotal`. A node whose last sweep is stale
keeps its last numbers, flagged — never dropped. The other five views
(addresses, consumers, sessions, connections, producers) are **live-through**:
one batched POST per serving node on demand, rows tagged with their logical node,
merged / filtered / sorted / paged in memory. A node that errors contributes
nothing; only when *every* node fails is the classified error surfaced (the
capability ledger + `broker.xml` advice).

### Event path (push, Phase 4)

The Core client (`artemis-jakarta-client`) subscribes to `activemq.notifications`
on every *serving* node of a cluster (ADR-0026). `CoreSubscriptionManager`
reconciles the subscription set against the live topology at the end of each
tier-A scrape, so a failover is followed — a subscription moves to the survivor,
not to a configured node. The subscriber polls `receive(timeout)` on a virtual
thread (a `MessageListener` deadlocks against `close()` on the pinned client),
and Studio drives its own reconnect with backoff (the broker advertises
connector hosts a client often cannot resolve). Each notification is normalised
to a `BrokerEvent` — a typed event with the address, consumer/session/connection
identity, timestamp, and the full `_AMQ_*` map — and handed to a buffered writer
(`BrokerEventWriter`, ADR-0028) that batch-inserts into `broker_event`. The
buffer is bounded; overflow increments a per-cluster `dropped` counter surfaced
by the events API rather than being silent. A reaper trims past a retention
window (default 72h, a `studio_setting`).

`NOTIFICATIONS` is no longer a fixed `UNKNOWN`: `CapabilityProbe` reads the
cached subscription verdict (`AVAILABLE` on ≥1 subscribed node; `UNAVAILABLE`
with the exact `broker.xml` security-setting or acceptor snippet when refused or
unreachable; `UNKNOWN` only until the first scrape). It opens no connection.

Message browse and send are served over the Core client when a subscription is
live (`MessageTransport`, ADR-0029): a `QueueBrowser` returns real byte bodies
(base64 with an encoding indicator), real typed properties, and does not
truncate. By-id / by-filter mutations stay on Jolokia. A page past the broker
page size falls back to Jolokia and every response says which channel served it.

### Realtime to the browser

One SSE endpoint, `GET /api/v1/stream?clusterId=&topics=…`, multiplexes named
events on a Spring MVC `SseEmitter` (ADR-0018; ADR-0010 removed WebFlux). The
signal topics (`topology`, `health`, `queues`, `consumers`, `sessions`,
`connections`) carry change *signals* (`{topic,clusterId,ts}`), not data — the
client refetches the matching TanStack Query key. The `events` topic is the
exception (ADR-0027): it carries the `BrokerEvent` payload and an `id:` line
(the `broker_event.seq`), and a reconnecting client replays what it missed via
`Last-Event-ID` (bounded to 500). Notification-driven staleness of the resource
views is fanned out as those signal topics, **coalesced to at most one per topic
per second per cluster** (`TopicCoalescer`) because each such refetch costs one
Jolokia call per node. A topic is published only when its state actually
changed; a 20s `:ping` comment keeps idle streams open and the response carries
`X-Accel-Buffering: no` (**proxies must not buffer this stream**). Two
consecutive `EventSource` failures ⇒ the client stops streaming and relies on
the 5s poll. See ADR-0003.

## State ownership

- **PostgreSQL** — clusters, nodes, credentials (AES-GCM), users, roles, audit,
  alert rules, request-reply expectations, operator settings (`studio_setting`),
  and the metrics cache.
- **URL** — navigable UI state (selected cluster, view, filter, sort, page). The
  frontend is file-tree-shaped routes over TanStack Router; every list view's
  `q` / `sort` / `page` lives in the query string.
- **In-memory** — the SSE subscriber registry, the split-brain corroboration
  ratchet + per-cluster refresh-cycle counter (`ScrapeCycle`), and the scrape
  scheduler's leadership. A restart re-derives all of it within ~one tier-A
  cycle. For multi-instance HA (post-MVP) the scheduler takes a Postgres
  advisory lock per cluster: one instance scrapes a cluster, every instance
  serves reads and SSE. The schema assumes this from day one.

## Broker transport and capabilities

See ADR-0002. A `CapabilityProbe` classifies a connection into
`MANAGEMENT_READ` / `MANAGEMENT_WRITE` / `NOTIFICATIONS` / `MESSAGE_IO` — four
classes, unchanged in Phase 3; the UI gates features on the result and shows the
`broker.xml` needed to unlock the rest.

Phase 3 message operations (browse, send, move / retry / delete / expire, purge)
are **Jolokia-only** (ADR-0021): they run entirely through `MESSAGE_IO`, one
batched POST per operation, no transport interface — that abstraction waits for
Phase 4's Core client, which will be the second real implementation. Bodies are
carried as text; the broker truncates oversized body / property values at
`management-message-attribute-size-limit` and Studio discloses that **per
message** (a `bodyTruncated` flag + the `broker.xml` snippet to raise the limit),
rather than as a fifth capability — slice 0 proved the limit is not readable back
over Jolokia. Faithful binary I/O is Phase 4.

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
before the broker call, updated with the outcome; a dry run is audited too
(`dry_run = true`). The actor is resolved before authentication exists
(ADR-0023): the security principal or the literal `anonymous`, plus the source IP
and an `X-Request-Id` (or a generated UUID); scheduler-originated rows are
`system`. The audit-log screen reads these back filtered by user / action /
outcome / time, newest first.

**Bulk safety cap.** A destructive message operation whose dry-run count exceeds
`safety.bulk-cap` (a `studio_setting`, default 1000) is rejected with a `422`
(`bulk-cap-exceeded`, carrying `affectedCount` and `cap`) unless the caller passes
`?override=true` — which the UI reaches only behind the dry-run preview plus a
typed confirmation of the queue name (ADR-0022). A cap that lived only in the
browser would not be a cap. The dry-run count itself is a broker-side estimate
(`countMessages(filter)` for a selector, the id count for an id list, the queue's
`MessageCount` for a purge or retry-all), labelled point-in-time.

**DLQ view.** Dead-letter and expiry addresses are read from the broker's own
`getAddressSettingsAsJSON` — never guessed from names (ADR-0022, D8). The view
lists the `queue_snapshot` rows on those addresses with per-node depth and a
"replay all" that runs a by-selector retry through the same preview + cap gate.
If the settings read fails the view says exactly that and infers nothing.

## Persistence notes

Liquibase (ADR-0008). Columns ordered by alignment to cut row padding;
per-table `autovacuum`/`fillfactor` on high-churn tables; `metric_sample`
range-partitioned with BRIN on `ts`. Server tuning in
`deploy/postgres/postgresql.tuning.conf`.
