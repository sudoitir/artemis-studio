## Context

See `proposal.md` — Why. Relevant current state: `006-alerting.sql` (Phase 1) is
applied but unused and does not fit what Phase 6/ADR-0012 built —
`MetricSampleWriter` only ever writes `subject_type='QUEUE'`, so state transitions
(split-brain, node down, replication desync) have no metric row to threshold against.
`queue_snapshot` holds the latest gauge value per `(node, queue)`; `metric_sample` is
history, bucketed by `MetricQueryService`/`MetricSeriesRepository` per ADR-0033.
`HaStateEvaluator`/`SplitBrainRegistry`/`ScrapeCycle` compute HA state and split-brain
corroboration on every tier-A tick but emit no transition event — reads recompute from
persisted state. `ScrapeScheduler` runs three independent `@Scheduled` trigger tasks
(tier A/B/C) via `SchedulingConfigurer`, per-cluster, per-node fan-out, network I/O
never inside a transaction (ADR-0015). `006` is released; a new changeset patches it,
following `011-request-reply-keys.sql`'s precedent for the same situation in Phase 5.

## Goals / Non-Goals

**Goals:**
- One rule model and one evaluation/debounce/delivery pipeline for both
  metric-threshold and HA-state-transition alerts.
- Alert latency bounded by scrape latency, with no new scheduled timer and no
  broker I/O inside evaluation.
- A `for_seconds` debounce that survives a process restart with zero in-memory state.
- A notification retry queue that survives a process restart and never pages twice for
  duplicate/near-simultaneous transitions on the same rule.
- Zero new Maven dependencies.

**Non-Goals:**
- Rollup/downsample tables — out of scope, unrelated to this change.
- Time-boxed silences/maintenance windows — `alert_rule.enabled` is the blunt
  instrument for Phase 7; add when an operator actually needs to silence a firing rule
  temporarily.
- Repeat/re-notify intervals — notify once on FIRING, once on resolve.
- An `EMAIL` channel — no SMTP dependency or config surface is added.
- Address- or broker-level metric rules — `metric_sample` has no such rows yet.
- Multi-instance evaluation locking — the `SELECT ... FOR UPDATE SKIP LOCKED` dispatch
  query is already safe under concurrent instances; the cross-cluster advisory lock for
  evaluation itself is the same v1.0 seam ADR-0015 already left open, not built here.

## Decisions

**1. `alert_rule.kind` is a discriminated union (`METRIC_THRESHOLD` | `STATE`), not
two separate rule tables or two separate features.**
A `CHECK` enforces that a `METRIC_THRESHOLD` row has `metric`/`comparator`/`threshold`
set and `state_condition` null, and a `STATE` row the reverse. Alternative considered:
model HA alerts as a wholly separate `ha_alert_rule`/feature, since they have a
different data source. Rejected — the OK→PENDING→FIRING→resolved state machine,
`for_seconds` debounce, firing history, delivery batching, routing, and UI are
identical for both; forking them duplicates five subsystems for a difference that is
one method (`AlertCondition.activeSubjects`) wide.

**2. Two data sources for `METRIC_THRESHOLD`, split on current-value vs.
rate-over-a-window, not one.**
Gauges (`messageCount`, `consumerCount`, `deliveringCount`, `scheduledCount`) read
`queue_snapshot` — the latest-per-`(node,queue)` cache already refreshed every
tier-B/C tick, indexed by `ix_queue_snapshot_cluster`. Rates (`messagesAdded`,
`messagesAcked`) read `metric_sample` via a new
`MetricSeriesRepository.latestRateBySubject(clusterId, metric, from, to)`:
`GREATEST(max(value) - min(value), 0) / windowSeconds`, `GROUP BY subject_name`,
window = 2 × the tier-B interval. This is the same restart-safe clamp ADR-0033 already
uses for chart rates, applied over one window instead of `date_bin` buckets.
Alternative considered: route every threshold rule through
`MetricQueryService.query(...)` uniformly, since `metric_sample` also has gauge
history. Rejected on two grounds — it is a bucketed *history* read with `date_bin`
epoch alignment overhead per call, and `metric_sample`'s index
`(cluster_id, subject_type, subject_name, metric, ts)` cannot serve "latest value per
subject" as cheaply as `queue_snapshot`'s current-state index already does; and a
"latest bucket" read against a table that can lag a full tier-B interval behind
`queue_snapshot` would report gauge alerts up to one interval stale for no benefit.

A subject with fewer than two samples in the rate window is **omitted from the
returned map**, not reported as rate `0`. Without this, a queue first seen this tick —
one sample, no prior value — would read as zero throughput and instantly fire a
"throughput dropped to zero" rule. Omission means "unknown," which the evaluator
treats as "condition not evaluable this tick," identical to how a state condition with
no data yet behaves.

**3. Evaluation runs inline after scrape-tier completion, not on an independent
timer.**
`ScrapeScheduler.tierA` calls `alertEvaluator.evaluate(clusterId, STATE)` right after
`scrapeCycle.corroborate(...)`/`streamSignals.afterTierA(...)`; the tier-B and tier-C
node fan-outs each call `alertEvaluator.evaluate(clusterId, METRIC_THRESHOLD)` after
their per-cluster join. Alternative considered: a fourth `@Scheduled` bean,
`scheduler/AlertEvaluationSweep`, mirroring `RrDeadlineSweep`. Rejected — an
independently-timed 15s sweep against a 15s tier-B schedule sees zero, one, or two new
snapshots per tick depending on phase drift, which jitters the `for_seconds` debounce
in a way that has nothing to do with the condition itself. Riding the scrape makes
alert latency track scrape latency exactly, and preserves ADR-0012's ~2-cycle (~10s)
split-brain detection ceiling with no added delay. Tier A completes per cluster even
when every node errors (ADR-0015 isolates per-node failure), so state rules always get
a tick to evaluate against.

Evaluation itself is DB-only (`queue_snapshot`/`metric_sample` reads plus
`alert_state`/`alert_firing` writes) and runs after the scrape's network I/O has
already completed and been persisted — it never opens a transaction spanning a broker
call, preserving ADR-0015's rule. It does not perform delivery HTTP calls; it writes
`alert_delivery` rows for `AlertDispatcher` to pick up.

**4. `for_seconds` debounce is a persisted `since` timestamp per `(rule, subject)`,
evaluated fresh from the full current subject set each tick.**
`alert_state.since` already exists. Each tick recomputes the *entire* current subject
set for the rule (every queue matching `scope`, or the rule's single state subject) and
walks: OK→PENDING on first active, PENDING→FIRING once `now - since >= for_seconds`
(immediately when `for_seconds = 0`), PENDING→OK or FIRING→OK the moment the condition
is false. Any `alert_state` row whose subject is absent from the current set is
resolved and its row deleted — the fix for the "queue deleted while PENDING/FIRING"
trap, which an incremental "only touch subjects seen this tick" design would miss
entirely (the row would never transition again). `QueueSnapshotUpsert.reapStale`
already guarantees `queue_snapshot` reflects genuine deletion within one tier-C sweep,
so "absent from the current set" means gone, not merely unsampled this tick.
Alternative considered: an in-memory `Map<(ruleId,subjectKey), Instant>` like
`ScrapeCycle`'s split-brain ratchet. Rejected — unlike the split-brain ratchet (whose
worst case on restart is one extra detection cycle, explicitly accepted in ADR-0012),
losing `for_seconds` progress on every restart would make a debounce interval
meaningless across routine deploys.

**5. Delivery is a durable Postgres queue (`alert_delivery`), claimed via
`SELECT ... FOR UPDATE SKIP LOCKED`, batched per `(rule, channel)` per evaluation
tick.**
`AlertEvaluator` writes one `alert_delivery` row per `(rule, channel)` per tick
carrying every transition (multiple subjects crossing at once) that tick produced for
that rule, not one row per `(firing, channel)`. A separate `scheduler/AlertDispatcher`
(`@Scheduled(fixedDelay)`) claims `PENDING` rows whose `next_attempt_at <= now`, sends,
and on failure sets `next_attempt_at` via the existing `Backoff` component (promoted
out of `broker.core`, not rewritten) with a 5-attempt cap before `DEAD`. Alternative
considered: one delivery row per subject transition. Rejected — it is the storm case:
a threshold rule matching 200 queues that all cross at once would produce 200 Slack
messages in the same second, which is worse than the alert being useful. Batching per
`(rule, channel, tick)` needs no additional grouping engine or cap; a single-subject
tick is simply a one-element payload list, so there is no special case to test.
`SELECT ... FOR UPDATE SKIP LOCKED` costs nothing at today's single-instance scale and
is already correct for the multi-instance seam ADR-0015 left open — no code changes
needed if that seam is ever used.

**6. Channels are global, not per-cluster; the sensitive part of a channel's config
is AES-GCM, following ADR-0009's existing mechanism through a new AAD overload.**
`notification_channel` (per `006`) has no `cluster_id`; that is kept — one Slack
workspace or webhook receiver commonly serves several clusters, and routing is already
expressed by which rules bind to which channel via the new join table.
`SecretVault.encrypt(...)`/`decrypt(...)` currently derive AAD as
`clusterId + "|" + kind`, binding ciphertext to a cluster row; a channel has no
cluster, so `SecretVault` gains an overload taking an opaque AAD string directly,
called with `channelId + "|" + kind`. This is a clarification of ADR-0009's existing
decision, not a supersession — the cipher (`AES/GCM/NoPadding`), key source
(`ARTEMIS_STUDIO_SECRET_KEY`), and "AAD binds ciphertext to its row" principle are
unchanged; only the AAD's *shape* generalizes from "always a cluster" to "the entity
that owns this secret." Alternative considered: add a nullable `cluster_id` to
`notification_channel` purely so the existing AAD shape keeps working. Rejected — it
would force every channel into a fake per-cluster identity to satisfy an
implementation detail of the vault, when the vault is the thing that should generalize.

**7. `EMAIL` is dropped from `notification_channel.kind`, not implemented and not
kept as a documented-but-unbuilt option.**
`006`'s CHECK allows `EMAIL`; no README task asks for it, and it would need an SMTP
dependency, a new config surface, and its own test path for zero requested behavior.
The new changeset narrows the CHECK to `WEBHOOK`/`SLACK`. If email is wanted later, it
is a new changeset re-widening the CHECK plus its own ADR for the SMTP choice — this
is a straightforward one-CHECK reopen, not a design debt.

**8. Built-in critical rules are seeded, ordinary, editable rows — not hard-coded
always-on checks.**
`ClusterService.register` inserts `SPLIT_BRAIN` (`for_seconds = 0`), `NODE_DOWN`
(`for_seconds = 30`), `REPLICATION_BEHIND` (`for_seconds = 120`) as normal
`alert_rule` rows for the new cluster, bound to no channel by default. Alternative
considered: evaluate these three unconditionally in code, independent of the
`alert_rule` table, since they are "built-in." Rejected — that makes them unroutable
(no channel to bind) and unsilenceable (no `enabled` flag to flip), which defeats the
purpose of having gotten routing and enable/disable right for everything else. An
operator who wants split-brain alerts to go to a different channel than depth alerts,
or wants to mute `REPLICATION_BEHIND` during a planned resync, needs them to be
ordinary rows.

## Risks / Trade-offs

- **[Risk]** A subject with no data this tick (rate condition, insufficient samples)
  is silently skipped rather than surfaced as an error. An operator might not notice a
  rate rule went dark because its metric stopped being sampled (e.g., a bug upstream). →
  **Mitigation**: `queue_snapshot`'s cache-freshness guarantee bounds this to genuine
  new/short-lived queues in normal operation; a metric-sample pipeline stall would
  independently starve the Phase 6 charts, which already have no data-gap alerting of
  their own — accepted as an existing gap, not a new one introduced here.
- **[Risk]** Riding scrape-tier completion means a cluster with an unusually slow tier
  (e.g., many nodes, contention on the per-node rate limiter) evaluates alerts less
  often than a fixed-interval sweep would. → **Mitigation**: this is the same latency
  an operator already accepts for the underlying data (`queue_snapshot`/health), so
  alert timeliness never claims to exceed data timeliness — it is the honest bound, not
  a regression.
- **[Trade-off]** Batching delivery per `(rule, channel, tick)` means a single
  Slack/webhook message can describe many subjects, which is less scannable than one
  message per subject for a small number of concurrent transitions. Accepted — the
  alternative (n messages) is strictly worse at the scale where it matters (many
  subjects crossing together), and the payload lists every affected subject explicitly
  so no information is lost, only presentation density changes.
- **[Risk]** `alert_delivery.payload JSONB` duplicates data already in `alert_firing`,
  so a schema change to one risks drifting from the other. → **Mitigation**: the
  dispatcher only ever reads `alert_delivery.payload` (never joins back to
  `alert_firing`) so there is exactly one writer and one reader per table; `alert_firing`
  is the durable history an operator queries, `alert_delivery.payload` is a
  point-in-time snapshot for retry, and losing sync between them after a rule is later
  edited is expected and harmless (the delivery already captured what happened at
  fire time).

## Migration Plan

- `013-alerting.sql` applies on top of the already-released `006-alerting.sql`,
  following the `011-request-reply-keys.sql` pattern of `ALTER TABLE` +
  new tables + a backfill `INSERT ... SELECT`, each in its own changeset with a
  `--rollback`.
- No existing data is at risk: `alert_rule`/`alert_state`/`notification_channel` are
  empty in every environment (Phase 7 is the first phase to write to them), so the
  `kind` default (`METRIC_THRESHOLD`) and CHECK additions are backward-compatible with
  zero rows to reconcile.
- Rollback is per-changeset `DROP`/`ALTER ... RESET` in reverse order, standard for
  this project; there is no data-loss concern given the tables are unpopulated before
  this change ships.
- `web/openapi.json` and `web/src/api/schema.d.ts` regenerate via
  `./mvnw test` (OpenApiSnapshotTest) then `npm --prefix web run gen:api`, same as
  every prior phase.
