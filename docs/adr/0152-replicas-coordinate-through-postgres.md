# ADR-0152: Replicas coordinate through Postgres: a notification bus, cluster ownership leases and a replica registry

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/11-high-availability`

## Context

Sessions are shared ([ADR-0123](0123-revoking-access-ends-sessions.md)) and installation-wide jobs run
once ([ADR-0125](0125-installation-wide-jobs-run-once-through-shedlock.md)), but several parts of Studio
still assumed a single instance:

- the SSE hub's registry is per instance ([ADR-0018](0018-sse-hub.md)), so an event reached only the
  clients of the replica that saw it;
- every replica polled, scraped and subscribed to every cluster, so two replicas doubled the load on
  every broker and wrote each broker notification twice;
- caches were invalidated only on the replica that made the change;
- bulk runs kept their stop flags in memory and every starting instance interrupted every running run
  ([ADR-0093](0093-bulk-operations-are-persisted-runs-over-single-queue-commands.md));
- the crash-loop guard counted boots in `studio_boot` ([ADR-0104](0104-studio-restarts-itself-for-plugins-when-supervised.md)),
  so a healthy second replica looked like a crash;
- nothing told a load balancer that a replica was starting or draining.

Postgres is already required and shared by every replica.

## Decision

Replicas coordinate only through Postgres. No new infrastructure is added.

1. **Replica registry.** Each process registers itself in `studio_replica`: its own id, host, version,
   state (starting, ready, draining, stopped) and a heartbeat in database time every 5 s, written from a
   dedicated thread. A replica whose heartbeat is older than 15 s is gone. A replica that is gone without
   a recorded stop is a crash, and the plugin crash-loop guard counts crashes, not boots. `studio_boot`
   is dropped.
2. **Notification bus.** Each replica keeps one dedicated connection that runs `LISTEN studio`.
   Messages are sent with `pg_notify` inside the sender's transaction, so they go out only on commit.
   Every replica, the sender included, handles every message on arrival. There are three payload
   shapes, and none of them names a Java type:
   - a stream frame;
   - a batch of `broker_event` seqs, which receivers load from the table;
   - a `(kind, key)` signal that invalidates a cache or wakes a component.

   A frame over 7,500 bytes goes out without its data, so clients refetch it (Postgres caps a payload at
   8,000 bytes). When the bus reconnects, a replica clears its caches and tells its stream clients to
   resync. Readiness fails while the bus has been down for more than 10 s.
3. **Stream fan-out.** `SseHub.publish` keeps its signatures and now goes through the bus; local
   delivery happens only on receipt. Replay no longer relies on the browser: the client sends the last
   event id it saw on every connect. The server holds live frames while it replays, and sends `resync`
   when the replay cap is hit. On drain it sends `reconnect` to its clients before closing their streams.
4. **Cluster ownership.** `cluster_lease` gives each cluster one owning replica. Owners are placed by
   rendezvous hashing over the live, ready replicas. A replica renews its leases every 5 s with a 15 s
   TTL, and treats a lease as lost once its last renewal is older than 10 s. Every timed per-cluster
   duty runs only on the owner: scrape tiers A and B, notification subscriptions, rr sampling, capture
   and index reconciliation, flow sampling, drift, setup review and plugin messaging. A draining replica
   drops its leases and signals the others to take over at once. The split-brain verdict moves from
   memory into `cluster_node`, so every replica's guards see it.
5. **Drain.** A new first shutdown phase refuses traffic, marks the replica draining, releases its leases
   and waits for the load balancer to notice. The stream phase follows, then a run phase that gives bulk
   runs and transfers 20 s before recording them interrupted with their progress. Probes are `/livez` and
   `/readyz`; readiness adds the `replica` indicator. Recovery interrupts only runs whose replica is gone.

## Consequences

- Any number of replicas behind a round-robin load balancer behave as one, with no sticky sessions.
  `deploy/compose/compose.ha.yaml` is the reference, and CI kills a replica in it on every pull request
  that changes the image.
- Adding a replica spreads clusters instead of multiplying broker load. A crashed owner's clusters are
  taken over within 20 s; a graceful stop hands them over within a second.
- Each replica holds one more database connection. Committing a transaction that notifies takes a global
  lock in Postgres, which is fine at Studio's volume (coalesced signals, one notify per flush) but rules
  the bus out for high-rate data.
- Stream data frames are limited to 7,500 bytes; plugin authors are told so on `SseHub`.
- A GC pause longer than the fencing margin can cause one duplicate scrape pass. There are no fencing
  tokens.
- The per-node limit on management calls applies to each replica separately for user-driven calls.
  Scheduled load has one owner.
- This supersedes the single-instance parts of [ADR-0018](0018-sse-hub.md) (the per-instance registry),
  [ADR-0093](0093-bulk-operations-are-persisted-runs-over-single-queue-commands.md) (in-process stops and startup recovery) and
  [ADR-0104](0104-studio-restarts-itself-for-plugins-when-supervised.md) (`studio_boot`).

## Alternatives considered

- **Redis or the broker as the bus.** Either would add infrastructure to operate, and neither is
  transactional with the writes it announces. Postgres is already there.
- **Spring Integration's Postgres channel.** It is heavier than the small listener Studio needs and
  brings its own message table.
- **One leader for all broker work.** It would leave every other replica idle and move every cluster at
  once on failover. Leases per cluster spread the load and move only what they must.
- **ShedLock per cluster per tick.** Notification subscriptions and capture drains are long-lived, not
  per tick, so they need ownership that lasts.
- **Sticky sessions.** They would not stop duplicate polling, and a replica failure would still drop
  streams.
