## Context

See proposal.md for why. Today, with N replicas on one Postgres:

- `kernel/stream/SseHub` is an in-memory registry per instance (ADR-0018). Only `events` has ids
  (`broker_event.seq`); `BrokerEventWriter` publishes before its transaction commits, using a global seq
  cursor. The browser client recreates its `EventSource` after an error, so `Last-Event-ID` is never sent
  and replay never runs. `StreamController` registers before replaying, so live frames can interleave.
- Installation-wide jobs run once through ShedLock (ADR-0125). Per-cluster work that calls brokers runs
  on every instance: scrape tiers A and B (and what hangs off them: node state, alert evaluation, plugin
  metrics, Core subscription reconcile), Core notification subscriptions (which insert duplicate
  `broker_event` rows), rr-sampler, message-index tails. Drift, setup review, flow sampling, capture
  reconcile and plugin messaging take a per-pass `ClusterLock`, but still poll from every replica.
- Caches are invalidated only locally: settings overrides, `ClusterEnvironmentIndex`, broker sessions of a
  deleted cluster, plugin activation, governance policy (which has a 30 s version probe).
  `SplitBrainRegistry` is in memory and read on safety paths.
- `BackgroundRuns` keeps stop flags in memory, and `BulkRecovery`/`TransferRecovery` interrupt every
  RUNNING row at any replica's startup. `studio_boot` counts every boot toward the crash loop, so a
  healthy second replica looks like a crash.
- `SqlQueryTickets` is in memory, so the POST and the stream GET must reach one instance.
- Probes are enabled, but nothing changes readiness, and shutdown has no drain step.

## Goals / Non-Goals

**Goals:** N replicas behind a round-robin load balancer, with no sticky sessions, behave as one; each
cluster's broker work runs on one replica; rolling restarts lose no events and fail no requests.

**Non-Goals:** active-active across regions, database HA, autoscaling, Kubernetes manifests (change 32),
one global rate limit per broker node across replicas.

## Decisions

### D1. Replica registry replaces `studio_boot`
`studio_replica(id uuid, host, version, state, started_at, heartbeat_at, stopped_at)`. Each process mints
its id at start. A dedicated platform thread heartbeats every 5 s, using database time; it does not run
on the job pool, because a starved pool must not look like a dead replica. "Live" means ready or starting,
with a heartbeat younger than 15 s. A crash is a row with no `stopped_at` and a stale heartbeat, so a
healthy peer is never counted. `PluginHost`'s crash-loop check counts crashes, not boots. Rows older than
one day are reaped by housekeeping.
*Alternative:* keep `studio_boot` next to a new table. Rejected: two tables for one concept, and the
crash-loop bug would stay.

### D2. Per-cluster ownership leases, placed by rendezvous hashing
`cluster_lease(cluster_id PK → cluster ON DELETE CASCADE, replica_id, expires_at)`. Every 5 s, in one
transaction, each replica:
1. renews its own leases; the rows the renew returns are what it owns;
2. releases any cluster whose rendezvous (HRW) owner, computed over the live ready replicas, is another
   replica;
3. acquires leases that are missing or expired and that it should own, or that have been expired for
   longer than one more TTL (orphans).

It also self-fences: `owns(c)` is false once its last successful renew is older than TTL minus 5 s on the
monotonic clock. When ownership changes, local `ClusterDutyAcquired`/`ClusterDutyReleased` events fire;
on release, subscriptions, capture consumers, `ScrapeCycle` and `StreamSignals` state are dropped, and on
acquire an immediate tier-A pass runs. Duties are gated by `RegisteredClusters.owned()` in the scrape
loops, and by `ClusterOwnership.owns` in the per-cluster jobs. User-triggered paths still run on the
replica that got the request, and `ClusterLock` still serialises them.
*Alternatives:* a single leader for all clusters (every replica but one would sit idle, and failover
would move everything at once); ShedLock per cluster per tick (tier A's Core subscriptions and capture
drains are long-lived, not per tick).
Default timing: renew 5 s, TTL 15 s (`artemis-studio.ha.*`).

### D3. A Postgres LISTEN/NOTIFY bus
`kernel/replica/StudioBus` holds one dedicated pgjdbc connection. It is opened from the
`spring.datasource` values, not taken from Hikari, because the connection must stay open for as long as
the process runs. It listens on `studio`. A `studio-bus` thread polls `getNotifications(1000)` and pings
the connection every 10 s. Publishing is `SELECT pg_notify('studio', ?)` through `JdbcTemplate`, so the
notification joins the caller's transaction and is sent only at commit. Every replica, the sender
included, handles a message when it arrives, so there is one path for all of them. There are three
payload shapes and none of them names a class:
- a stream frame `{clusterId, topic, data?, id?}`;
- an events batch, a list of seqs that receivers load with `seq = ANY(?)`;
- a signal `(kind, key)`, re-published locally as a `ReplicaSignal`.

A frame larger than 7,500 bytes is downgraded to a signal-only frame (Postgres caps a payload at 8,000
bytes). When the bus reconnects, a local `BusResumed` event clears every cache and sends `resync` to
every stream. While the bus has been down for more than 10 s, readiness fails.
*Alternatives:* Redis or Artemis as the bus. Rejected: they add infrastructure, whereas Postgres is
already required and pg_notify is transactional. Spring Integration's Postgres channel is heavier than
the roughly 100 lines this needs.

### D4. Stream fan-out, replay and drain
`SseHub.publish` keeps its `@PluginApi` signatures. It now broadcasts on the bus, and its old body becomes
the local `deliver`, so plugins get fan-out without changing anything. `BrokerEventWriter` takes the new
seqs from the insert, not from a global cursor, and notifies them inside its flush transaction; the
coalesced derived topics are touched only on the replica that wrote. `StreamController`:
- accepts `lastEventId` as a query parameter or header;
- registers the subscriber in buffering mode, replays, then flushes the buffer, skipping ids it has
  already replayed;
- sends `resync` when the replay hits its cap;
- answers 503 while draining.

The client tracks the last `events` id and sends it on every connect. On `reconnect` it reconnects
immediately with its backoff reset. On `resync`, or on any reconnect after a failure, it invalidates the
cluster's queries.

### D5. Signals for cache coherence
| kind | handled by |
|---|---|
| `settings` | `SettingsService` reloads overrides and pushed values |
| `cluster-deleted` | broker sessions released, environment index invalidated, cluster state forgotten |
| `env-index` | `ClusterEnvironmentIndex.invalidate` |
| `policy` | `PolicyStore` reload; the 30 s probe job is deleted |
| `plugins` | `PluginHost.reconcileRuntimes` brings local runtimes in line with `plugin_install` |
| `session-ended` | `SseHub` closes that session's streams (the 10 s check stays as a backstop) |
| `run-stop` | `BackgroundRuns.requestStop` |
| `leases` | immediate ownership tick |

`FeatureRegistry.manifestVersion` becomes a hash of the active `id@version` set, so a load balancer
alternating between replicas cannot make the web manifest flap. `SecretVault` keeps its 10 s refresh,
because keys may be rotated outside Studio.

### D6. Split-brain verdict persisted
The owner writes `cluster_node.split_brain` in the same tier-A persist. `SplitBrainRegistry` is deleted,
and its readers (transfer and config-apply guards, topology, cluster read) read the rows they already
load. `ScrapeCycle` and `StreamSignals` stay local to the owner: after a handover they cost one extra
cycle, which is the same cost as a restart. `NodeCallHealth` stays per replica on purpose, and the
self-health view says whose view it is.

### D7. Small shared stores
- SQL query tickets move to an UNLOGGED `sql_query_ticket` table and are redeemed once, with
  `DELETE … RETURNING`.
- The API-token per-minute window moves to an UNLOGGED `api_request_window` table (`UPSERT … RETURNING`),
  so the limit is exact across replicas. In-flight concurrency stays per replica.
- The login throttle stays in memory, because the database account lock (ADR-0144) is the real control.

### D8. Probes and drain
`management.endpoint.health.probes.add-additional-paths=true` serves `/livez` and `/readyz`. Readiness is
`readinessState` plus a `replica` indicator, which is up once the bus listens, the plugin boot has
finished and the replica is ready, and down while draining or after 10 s without the bus. The existing
`studio` group stays out of both probes. Liquibase runs before the web server starts, so a startup probe
gets connection refused until migrations are done.

Shutdown phases, in order (`timeout-per-shutdown-phase: 30s`):
1. **DRAIN** (new, first): publish `REFUSING_TRAFFIC`, set the replica to draining, delete its leases and
   broadcast `leases`, then wait `drain-delay` (5 s, longer than the load balancer's detection time).
2. **STREAM**: send `reconnect`, then close the streams.
3. **RUNS** (new): wait up to 20 s for bulk runs and transfers, then stop them. The runners record
   INTERRUPTED with their progress.
4. The existing phases.
5. Write `stopped_at`.

### D9. Runs across replicas
`bulk_run` and `transfer_run` get a `replica_id` column. Recovery becomes an installation-wide job (every
30 s, and once at startup) that interrupts only RUNNING runs whose replica is not live. The stop
endpoints set the stop and broadcast `run-stop`, so the replica executing the run acts on it.

### D10. Reference deployment and failover tests
- `deploy/compose/compose.ha.yaml`: Postgres, `studio-1` and `studio-2` (the second waits for the first
  to be healthy, so migrations run once), and HAProxy 3 with `balance leastconn`,
  `option httpchk GET /readyz`, `inter 1s fall 2 rise 2`, 60 s client and server timeouts (above the SSE
  heartbeat), no stickiness, and `stop_grace_period: 60s`.
- `compose.ha.test.yaml` adds a broker.
- `web/scripts/ha-failover.ts` (Node, no dependencies) registers the cluster and holds an SSE stream and a
  request loop through the load balancer. It checks that:
  - after studio-1 is SIGKILLed, requests recover within a few seconds, the stream resumes from its last
    id with no gap, and scraping resumes within 25 s;
  - after studio-1 restarts and studio-2 is stopped gracefully, `reconnect` arrives, no request fails, and
    the event ids have no gap.
- It runs in CI's `image` job against the image that job builds.
- `HaReplicasIT` boots two application contexts on one database to cover fan-out, replay, cache
  coherence, one owner per cluster, takeover, per-node request counts and run recovery.

## Risks / Trade-offs

- [A GC pause longer than the fencing margin can cause one duplicate scrape pass] → self-fencing at
  TTL − 5 s bounds it to one pass; a `ponytail:` note records the ceiling (fencing tokens).
- [Committing a transaction that sent a notify takes a global lock in Postgres] → notifies are coalesced
  and batched (one per flush); the volume is low.
- [The per-node management-call limit applies per replica for user-driven calls] → scheduled load is
  single-owner; this is documented in the guide.
- [Prometheus series for plugin metrics move between replicas when ownership moves] → documented; scrape
  every replica.
- [SseHub fan-out now goes through the database] → a frame over 7,500 bytes is downgraded, and the limit is
  documented on `SseHub`.

## Migration Plan

No compatibility (standing decision). New changesets drop `studio_boot` and add `studio_replica`,
`cluster_lease`, `sql_query_ticket`, `api_request_window`, `cluster_node.split_brain`, and `replica_id`
on the run tables. Single-instance installs behave as before, with one replica owning every cluster.
Compose healthchecks move to `/readyz`.
