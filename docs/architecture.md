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
  MCP client                        │
  ──────────────────────────────────┤  JSON-RPC over HTTP, /mcp
   (Bearer as_…)                    │  (ADR-0045, ADR-0046)
                    ┌───────────────▼─────────────────────────────┐
                    │  Spring Boot 4.1 · Java 25                   │
                    │                                              │
                    │  web/        controllers, DTOs, SSE hub       │
                    │  security/   session/token/OIDC auth, RBAC    │
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

## The SQL Console, the message index and message capture

The console is one editor over three sources of the same shape (ADR-0058): a live
broker read, the persisted `message_index`, and a live tail of either. `SELECT ...
FROM broker."ORDER.#"` fans out to every serving node, pushes down what Artemis'
own filter syntax can express, and evaluates the rest in Studio. Every bound it
reaches — target cap, scan cap, row cap, timeout — is reported in words, because a
bounded result that does not say so reads as a complete one.

**Two ways a message reaches the index, and they make different claims.**

- **Sampled** (ADR-0060). A poll browses the queue against a `(timestamp, messageId)`
  high-water mark and records what it saw. Complete only for messages that sit still
  long enough to be seen. A queue that drains faster than the interval is invisible,
  which the console states permanently and non-dismissably on every sampled tail.
- **Captured** (ADR-0062). A non-exclusive divert copies the address into a
  Studio-owned queue, drained by a Core consumer. Complete for what the address
  routed, whether or not anything consumed it in between.

```
source address ──non-exclusive divert──► artemis-studio.capture.<instance>.<address>.<sub>
      (per node)                              ring-size N, address-full-policy=DROP,
                                              expiry-delay, non-durable
                                                       │
                                              Core consumer (CorePool, CLIENT_ACKNOWLEDGE)
                                                       │
                                                  CaptureBus
                                     ┌─────────────────┼─────────────────┐
                                 live tail      message_index     request-reply
```

The tap is bounded by construction, which is what makes it safe to leave running:
the ring drops the oldest message rather than growing, `DROP` keeps the broker from
paging or blocking on Studio's account, and `expiry-delay` with
`auto-create-expiry-resources=false` bounds it in age too. None of those objects
disappears on a broker restart (ADR-0065 — measured, not assumed), so **every
removal is an explicit act**: `CaptureReconciler` is the only thing that takes a tap
away.

That reconciler is the whole lifecycle. Desired state is the `CAPTURE` subscriptions
in Postgres, resolved through the same planner an operator's own query uses; actual
state is each live node's divert names filtered to this Studio's instance id. One
idempotent loop is simultaneously the install path, the crash-recovery path, the
failover path (a promoted backup carries no divert, and the next pass installs one)
and the removal path. It acts on drift only, through `NodeCallLimiter`, and takes a
per-cluster Postgres advisory lock so two instances of the same Studio cannot fight.

Two preflight refusals, both because the alternative fails silently: an **exclusive
divert** already on the source address would shadow ours and capture nothing, and a
capture queue Studio cannot **restrict with a `security-setting`** would be an
unguarded second copy of production payload. Both refuse with the `broker.xml` that
would change the answer.

Identity comes from Studio's own state first (D2): the capture queue names the source
address, so a captured row is found by the **original** queue name without depending
on a broker header. `_AMQ_ORIG_MESSAGE_ID` supplies the source message id on top; when
it is absent, "verify on broker" is offered and disabled with the reason, never hidden.

Capture is address-scoped and says so. A divert copies at address routing, before
multicast fan-out, so for an address with several bound queues the index holds one
row and genuinely cannot say which subscriptions received it.

## Safety and audit

Every mutating endpoint accepts `?dryRun=true` and returns the affected count
without acting. Purge/delete require typed confirmation in the UI. Every mutation
writes an `audit_event` in the same transaction as the command — row created
before the broker call, updated with the outcome; a dry run is audited too
(`dry_run = true`). The actor is a real identity (ADR-0041): the authenticated
principal's username and `user_id`, with the token name folded in when the
caller authenticated via an API token (`"<owner> [token: <name>]"`), plus the
source IP and an `X-Request-Id` (or a generated UUID); scheduler-originated
rows are `system`; the literal `anonymous` is reachable only for a failed
login attempt itself, since every other mutating call requires authentication
(ADR-0037). The audit-log screen reads these back filtered by user / action /
outcome / time, newest first.

**Bulk safety cap.** A destructive message operation whose dry-run count exceeds
`safety.bulk-cap` (a `studio_setting`, default 1000) is rejected with a `422`
(`bulk-cap-exceeded`, carrying `affectedCount` and `cap`) unless the caller passes
`?override=true` — which the UI reaches only behind the dry-run preview plus a
typed confirmation of the queue name (ADR-0022). A cap that lived only in the
browser would not be a cap. The dry-run count itself is a broker-side estimate
(`countMessages(filter)` for a selector, the id count for an id list, the queue's
`MessageCount` for a purge or retry-all), labelled point-in-time.

**Broker configuration** (ADR-0067). A cluster's declared address settings,
security settings, diverts and queues live in Postgres (`broker_config_*`,
changeset 024), versioned on every save. `BrokerConfigApplyService` is a
separate engine from the queue lifecycle because its fan-out is deliberately
not a fan-out: a plan is computed per live node from at most two batched reads
(diff-driven, `ALREADY` where the read-back matches), hazards are classified
before any write and the High ones must be acknowledged by id, then the canary
node receives every step and is read back before the next node is touched.
The first failure halts the run — remaining nodes report `NOT_ATTEMPTED`,
nothing is rolled back, and re-running converges. A real run names the plan
hash it previewed and is refused (`409 plan-changed`) if the cluster moved; a
Postgres advisory lock (`ClusterLock.Scope.CONFIG_APPLY`) refuses a concurrent
apply. Studio removes only what it applied, never destroys a queue or address,
never writes `broker.xml` and never calls `reloadConfigurationFile`.
`BrokerConfigDriftService` evaluates every live node on a schedule
(`config.drift-interval`) and after every apply; it writes state, publishes the
`config` SSE topic and feeds the `CONFIG_DRIFT` alert condition — evaluation is
scheduled, action never is.

**DLQ view.** Dead-letter and expiry addresses are read from the broker's own
`getAddressSettingsAsJSON` — never guessed from names (ADR-0022, D8). The view
lists the `queue_snapshot` rows on those addresses with per-node depth and a
"replay all" that runs a by-selector retry through the same preview + cap gate.
If the settings read fails the view says exactly that and infers nothing.

## MCP surface

`POST /mcp` is a second inbound edge onto the same services (ADR-0045): sixteen
intent-shaped tools, six resources and four runbook prompts, mounted by
Spring AI's WebMVC starter as a stateless Streamable HTTP transport. It is an
adapter and nothing more — `mcp/**` holds argument coercion, its own lean
projections and the error mapping, and calls the same `service/**` methods the
controllers do.

Everything above therefore applies unchanged: `ClusterAccessGuard` and
`@PreAuthorize` are the enforcement, the bulk cap is the same `studio_setting`,
and every mutation writes the same `audit_event` under the key owner's identity
with the key's name attached. Authentication is the ADR-0039 personal API tokens
(ADR-0046), so a key never exceeds its owner's live grants.

Two things are specific to this edge. Tool bodies must run on the servlet thread
(`type: SYNC`), because permission and actor resolution both read `ThreadLocal`
state. And mutations add a model-facing gate on top of the existing ones:
`dryRun` defaults to true, and a real destructive run requires `confirm` to equal
the subject's name — separate from, and never satisfied by, the bulk-cap
`override`.

## Persistence notes

Liquibase (ADR-0008). Columns ordered by alignment to cut row padding;
per-table `autovacuum`/`fillfactor` on high-churn tables; `metric_sample`
range-partitioned with BRIN on `ts`. Server tuning in
`deploy/postgres/postgresql.tuning.conf`.
