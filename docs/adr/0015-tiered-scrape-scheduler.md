# ADR-0015: Tiered scrape scheduler; the refresh cycle counter is scheduler-owned per cluster

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi

## Context

Phase 1's `HaRefreshTask` is a single `@Scheduled` method: it loops every cluster
and every node serially, holds one `@Transactional` open across the whole tick,
and makes each node's Jolokia HTTP call *inside* that transaction. It reads only
tier-A state (HA attributes + topology) and carries one global `AtomicLong`
cycle counter. Its own Javadoc says Phase 2 deletes it.

Phase 2 adds queue scraping. `docs/architecture.md` specifies three tiers — A
(5s, HA + topology + broker counters), B (15s, busy queues), C (5m, idle queues)
— and `docs/broker-management-notes.md` establishes the plan: one batched Jolokia
POST per node per tier tick, paging large queue sets across successive scrapes.
CLAUDE.md non-negotiable #1 requires this and a per-node call ceiling so Studio
can never be the reason a broker falls over.

ADR-0012 introduced the corroborated split-brain check and explicitly deferred
"where this cross-run state lives" to "Phase 2's scheduler-ownership model". The
Phase 1 `HaStateEvaluator` mutates its `firstSuspectedCycle` map, and because
`toLogicalNodes` calls `evaluateSplitBrain`, every *read* endpoint
(`GET /topology`, `/health`, `list()`) currently advances the ratchet, not just
the refresh loop.

## Decision

We will replace `HaRefreshTask` with a `scheduler/ScrapeScheduler` running three
independent `@Scheduled` methods on a pooled `TaskScheduler` (virtual-thread
executor; `spring.task.scheduling` pool config). The intervals stay
`artemis-studio.scrape.tier-{a,b,c}-interval`, overridable at runtime via
`studio_setting` (ADR-0021 area / `studio-settings` capability).

- **Tier A** — per manageable node, one `JolokiaBrokerClient.batch()` POST:
  HA attributes + `listNetworkTopology()` + broker counters. Reproduces
  `HaRefreshTask`'s behaviour exactly, in one POST instead of two.
- **Tier B** — per node, one batched POST carrying one `listQueues` page of the
  *hot* queues (consumers > 0 or depth > 0 at last reading).
- **Tier C** — per node, one batched POST carrying one `listQueues` page,
  advancing a per-node sweep cursor until the whole queue set is covered, then
  idling until the next 5-minute mark.
- **Network I/O never runs inside a database transaction.** Per node: acquire
  the per-node limiter, POST, parse the response, then hand a plain result
  object to a short `@Transactional` persist step. A hung broker holds no DB
  transaction for its read-timeout duration.
- **One node's failure is isolated** — caught, written to `broker_node.last_error`,
  never propagated; every other node and cluster still scrapes.
- **The refresh-cycle counter and the one-cycle split-brain corroboration state
  are owned by the scheduler, per cluster.** A `scheduler/ScrapeCycle` component
  holds a per-cluster `AtomicLong` and the per-`(cluster, node)` first-suspected
  cycle. Only tier A advances them. `HaStateEvaluator` keeps its pure derivations
  and loses its mutable map; `GET /topology` and `/health` are served from the
  last persisted scrape and do not advance the ratchet (see the `cluster-topology`
  spec delta).
- **The ~2-cycle (~10s) worst-case split-brain detection ceiling is preserved**
  for whatever `tier-a-interval` is configured.
- The per-cluster shape leaves the seam for multi-instance HA (a Postgres
  advisory lock per cluster, `docs/architecture.md`) — not built here.

## Consequences

- A slow or unreachable broker no longer stalls other clusters or holds a
  connection-pool transaction. Tier work runs concurrently on the pool.
- `HaRefreshTask` and `HaRefreshTaskTest` are deleted. `ScrapeSchedulerTest`
  asserts tier-A parity with the retired behaviour.
- Split-brain state is now in one place with one writer. Reads are cheap and
  side-effect-free. On a process restart mid-split-brain the status momentarily
  drops to `SUSPECTED` and re-escalates next cycle — unchanged from ADR-0012,
  accepted.
- Three schedules instead of one, plus a pool to size. The pool is bounded and
  virtual-thread-backed; the cost is a few lines of `application.yml`.
- Tier B depends on being able to identify hot queues. If the broker does not
  honour `listQueues` sort/predicate options (unverified before this phase),
  tier B degrades to an unfiltered page plus Studio-side classification from the
  last `queue_snapshot`; the scheduler carries that branch.

## Alternatives considered

- **Keep one `@Scheduled` method, add tiers as branches.** The serial,
  single-transaction, HTTP-in-transaction shape is the actual problem; more
  branches in it do not fix that. Rejected.
- **Quartz / Spring Batch.** Job store, triggers, listeners — far more machinery
  than three fixed-delay methods and a bounded pool need. Rejected.
- **Keep the cycle counter global.** Two clusters would share one counter, so a
  cycle boundary for one is arbitrary for the other, and the ADR-0012
  "same cycle" corroboration becomes meaningless across clusters. Rejected.
- **Single batched read of a whole pair for the split-brain check** (ADR-0012's
  rejected alternative) — still couples the check to a multi-node batch shape
  the per-node model does not have. Unchanged: rejected.
