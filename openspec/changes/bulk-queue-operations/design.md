## Context

See proposal.md for why. What exists and shapes the approach:

- **Single-queue commands.**
  - `platform/clusters/BrokerCommands.run(Command)` already does these steps:
    1. audit begin;
    2. per-node fan-out (one target per logical node, not-live nodes skipped);
    3. preflight;
    4. estimate against `safety.bulk-cap` (ADR-0022), with override;
    5. sequential node calls without rollback;
    6. audit finish with per-node detail (ADR-0078).

    It returns `LifecycleOutcome`.
  - The public entry points are:
    - `QueueLifecycleService.deleteQueue(clusterId, q, dryRun, override, disconnectConsumers)`
    - `QueueLifecycleService.setPaused(...)`
    - `MessageService.purge(clusterId, q, nodeId, dryRun, override)`, which is one node
      per call and returns `Attempt<Outcome>`.
- **Queue list.** `feature/resources` `CrossNodeAggregator.queues(clusterId, ResourceQuery)`
  resolves the queues screen's list, `?q=` filter included. It does this from one batched
  Jolokia read per node, with per-node message and consumer counts.
- **Rate limiting.** `platform/broker/NodeCallLimiter` rate-limits every Jolokia call per node.
- **Jobs.** `kernel.jobs` offers scheduled jobs only. There is no user-submitted async job
  facility.
- **Audit grouping.** `audit_event` has no grouping column.
- **Frontend.**
  - `ui/VirtualTable` supports `selectable`; `features/messages/MessagesView` is its only
    user.
  - `ui/ConfirmByTyping` and `ui/NodeOutcomeSummary` are the reuse points the operator-ui
    rules name.

## Goals / Non-Goals

**Goals:**
- One bulk run = one operation × a frozen, hashed set of queues, executed sequentially
  through the existing single-queue commands. Existing safety, audit and rate limiting
  apply unchanged.
- Survives page reloads, and is honest across Studio restarts.
- No signature changes to existing services or `BrokerCommands`.

**Non-Goals:**
- Parallel execution across queues. Sequential is the broker-friendly default, and the
  per-node limiter already paces calls within a queue.
- A general async-job framework in the kernel. There is one consumer; extract when a
  second appears.
- Multi-instance Studio coordination. Studio runs as one instance per deployment
  (compose/Helm), and the stop flag is in-process.
- Bulk move/retry, and bulk over MCP.

## Decisions

### D1. A new module `feature/bulk`, not code inside `feature/queues`
Bulk orchestrates three features: resources for resolution, queues for pause, resume and
delete, and messages for purge.
- Putting it in `queues` would give `queues` an edge to `messages` and `resources`.
- A module of its own keeps those edges one-directional (bulk → the others) and gives the
  bulk tables one owner.

`allowedDependencies`:
- `feature.queues`, `feature.messages`, `feature.resources`
- `platform.clusters`, `platform.broker`
- `kernel.audit`, `kernel.core`, `kernel.plugin`, `kernel.security`, `kernel.settings`,
  `kernel.stream`

If the queues/messages/resources types it needs are not already public, they are exposed
as named interfaces, not reached into.

### D2. Preview = resolve + estimate from the aggregated list; never a dry run per queue
- **Resolution.** The preview calls `CrossNodeAggregator.queues` for every page of the
  filter, or for the explicit names.
- **Figures.** Per-node message and consumer counts come from that one batched read per
  node (non-negotiable #1). A node that did not answer gives `null` figures, which reads
  as "unknown", never 0.
- **Refusals** are computed from the same data, with the rules the single-queue preflight
  applies:
  - a delete of a queue with consumers is refused unless the run disconnects consumers;
  - a queue that no longer exists is refused;
  - a pause of a queue that is already paused is recorded as "already", which is success.
- **The authoritative preflight** still runs per queue at execution inside
  `BrokerCommands`, so a stale preview cannot do harm; it can only fail an item.

*Alternative rejected*: a real dry run per queue. That is N × nodes Jolokia calls for 200
queues, exactly what non-negotiable #1 forbids.

### D3. Persisted run with a plan hash; a DB constraint for one active run per cluster
Two tables in `db/changelog/feature/bulk/`. Columns follow the row-padding order.

- `bulk_run`:
  - `created_at, started_at, finished_at, expires_at timestamptz`
  - `estimate bigint` (null = unknown)
  - `total_items, succeeded, failed, skipped int`
  - `operation, status, username, plan_hash, error text`
  - `selection, options jsonb`
  - `id, cluster_id, audit_event_id uuid`
  - `estimate_complete, override_cap, continue_on_failure bool`
- `bulk_run_item`:
  - `started_at, finished_at timestamptz`
  - `affected bigint`
  - `ordinal int`
  - `queue_name, status, error text`
  - `estimate, outcome jsonb`: the per-node figures, and the item's per-node outcome in
    `LifecycleOutcome` shape
  - `id, run_id uuid` (FK to `bulk_run`, `ON DELETE CASCADE`)
  - Table parameters: `fillfactor=80` and tight autovacuum, because it is updated once
    per item transition.

Other decisions:
- **One active run per cluster** is a partial unique index
  `ON bulk_run(cluster_id) WHERE status = 'RUNNING'`. That is a database constraint, not a
  check-then-act race.
- **`plan_hash`** is SHA-256 over the operation, the ordered queue names, and the options
  that change behaviour. Execute must echo it.
- **Housekeeping.** A `PREVIEWED` run that expires is deleted by a daily housekeeping job.
  Finished runs are kept; they are small, and the audit trail already outlives them.

### D4. Execution: a virtual thread per run, acting as the initiating operator
- `POST …/execute` validates, flips the run to `RUNNING` (the unique index enforces
  exclusivity), commits the parent audit event (`bulk.<operation>`), and hands the run to
  `BulkRunner`. `BulkRunner` starts a virtual thread and returns 202 at once.
- **Operator identity.** The runner captures the operator's `SecurityContext`, plus
  whatever the audit `Actor` resolves from (user, request id, source IP), at execute. It
  installs them on the worker thread, so `ClusterAccessGuard.requireCluster` re-checks
  the real grant for each item, and each child audit row is attributed to the operator.
- **Per item**, in order:
  1. mark `RUNNING`;
  2. call the single-queue command with `dryRun=false`:
     - `override` = the run's `override_cap`
     - pause/resume: `setPaused`
     - delete: `deleteQueue`, with the run's `disconnectConsumers` option
     - purge: `MessageService.purge` once per hosting node, with the per-node results
       folded into one `LifecycleOutcome`-shaped outcome
  3. classify: all nodes applied/already = SUCCEEDED; mixed = PARTIAL; none = FAILED;
  4. persist the item;
  5. update the run counters;
  6. publish SSE.
- **Stop policy.** A FAILED or PARTIAL item without `continue_on_failure` marks the rest
  SKIPPED.
- **Stop.** `POST …/stop` sets an in-process flag, checked between items; the rest become
  CANCELLED.
- **Finish.** The runner sets the final status, `finished_at`, and finishes the parent
  audit event (`finish(event, anyFailed, affected, error, detail)`).
- **Exceptions.** An exception escaping an item's command is that item's FAILED outcome,
  never the thread's death. The runner's outer `finally` always writes a terminal run
  status.

### D5. Audit parent via a `ScopedValue`, not new parameters
`kernel.audit` gains:
- a changeset adding nullable `parent_id uuid` to `audit_event`, with an index;
- `AuditScope.PARENT`, a `ScopedValue<UUID>`.

`AuditService.begin` reads `PARENT` when it is bound. The runner wraps each item in
`ScopedValue.where(AuditScope.PARENT, runEventId).run(...)`, so none of the dozen callers
of `BrokerCommands` or `AuditService` change. `ScopedValue` is final in Java 25 and bound
per call, so it cannot leak into another request the way a `ThreadLocal` can.

The audit API/view exposes `parentId`. It also offers a `parentId` filter, which shows a
run's children.

*Alternative rejected*: a `parentId` parameter threaded through `Command`, the services
and the controllers. That is a wide signature change for one caller.

### D6. Startup recovery marks, never resumes
On `ApplicationReadyEvent`, for each run with status `RUNNING`:
- the run becomes `INTERRUPTED`;
- its `RUNNING` item becomes `UNKNOWN`, with error "Studio stopped while this queue was
  being acted on; check the broker";
- its `PENDING` items become `CANCELLED`;
- its parent audit event is failed.

Resuming would act on a queue set whose preview is stale, with an operator who is not
watching. Safe by default says no.

### D7. Settings and SSE
- **Setting** `safety.bulk-queue-cap`: integer, default 200, min 1. It is registered
  where `BrokerSettings` registers `safety.bulk-cap`, so the Settings screen shows the
  two caps together.
- **SSE topic** `bulk`, `carriesData=true`:
  `{runId, status, succeeded, failed, skipped, total}`. It is published per item and at
  the terminal state. The frontend invalidates the run query on each frame.

### D8. Frontend: a kernel slot keeps queues ignorant of bulk
- `kernel/slots.ts` gains `queues.selection` with props `{clusterId, selection, count,
  clear}`, where
  `selection = {kind:'names', names:string[]} | {kind:'filter', q:string, total:number}`.
- `features/queues/QueuesView` owns the selection state, local and reset when the filter
  changes. It renders the "select all N matching" banner, and a sticky action region that
  renders the slot's contributions.
- `features/bulk` contributes `BulkActionBar`:
  - it opens `BulkPreviewDialog` (preview → confirm → execute);
  - `/clusters/$clusterId/bulk/$runId` is the run view (`BulkRunView`);
  - `/clusters/$clusterId/bulk` is the history (`BulkRunsView`), in the operations nav
    group.
- **Reuse**:
  - confirmation: `ui/ConfirmByTyping` (token `"<verb> <n> queues"`);
  - per-node detail: `ui/NodeOutcomeSummary`;
  - tables: `ui/VirtualTable`;
  - permission gating: `kernel/auth/useCan`.
- **Permissions per action**:
  - pause/resume: `queue:pause`
  - delete: `queue:delete`
  - purge: the messages purge permission, `MessagePermissions.QUEUE_PURGE`

## Risks / Trade-offs

- [A 200-queue delete takes minutes] → The run is async, with live progress and stop.
  The per-node limiter protects the broker whatever the operator's patience.
- [The preview goes stale before execute] → The set is frozen and hashed, and the preview
  expires after 10 minutes. The authoritative preflight re-runs per queue at execution,
  so staleness can only fail an item, not widen the blast radius.
- [The in-process stop flag and runner assume one Studio instance] → That is true of every
  supported deployment today. The unique index stops two instances from running two runs
  on a cluster. A second instance would still need a DB-polled stop flag; recorded in
  ADR-0093.
- [The worker thread acts without an HTTP request] → The captured identity is re-checked
  per item against current grants. Revocation takes effect at the next item.
- [Purge per node yields several child audit rows per queue] → They all carry the run as
  parent, and each is one real broker action. That is correct, not noise.

## Migration Plan

Additive only:
- two new tables, with their changelog included from the master;
- one nullable column and an index on `audit_event`;
- one new setting with a default.

Rollback is the Liquibase rollback of those changesets. No existing behaviour changes.
