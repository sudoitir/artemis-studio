## Context

See `proposal.md` — Why. The design-relevant state of the codebase:

- **`feature/triage` already exists and is MCP-only.** Its `package-info.java` declares
  edges to `feature.resources`, `feature.metrics`, `feature.events`, `feature.alerting`,
  `kernel.security` and `platform.clusters` — the exact set a consumer-health verdict
  needs. It has no REST controller and no frontend feature.
- **Its diagnosis logic lives in the MCP adapter.** `mcp/TriageMcpTools.java` computes a
  trend as first-versus-last ±10% returned as a *string*, and a `slowConsumerVerdict`
  guarded by a magic ×10. That file's own doc comment says "Every method is a thin adapter
  over the services the REST layer already uses" — which is currently untrue, because
  there is no such service.
- **The velocity primitive already exists.** `MetricSamples.latestRateWithTimeBySubject`
  returns a per-node-summed rate with its `asOf` and `span`, restart-clamped. It was added
  for Flow and is exactly what this needs.
- **The correct detection triple already exists** in
  `feature/alerting/SlowConsumerCondition` per ADR-0044: consumers attached, backlog
  present, acknowledgements near zero, paused excluded.
- **`queue_snapshot` carries `delivering_count`; `metric_sample` does not.** The current
  in-flight value is available now; its history is not.
- **`metric_sample.metric` and `alert_rule.metric` are `text` columns**, so new metric
  names and new rule kinds need no migration.

## Goals / Non-Goals

**Goals:**

- One evaluation of the verdict, consumed by the screen, the REST API and MCP.
- Root-cause discrimination strong enough to separate a blocked consumer from a
  misconfigured selector, because they lead to opposite actions.
- No added broker load, and no new persisted state.
- Make `TriageMcpTools` the thin adapter it already claims to be.

**Non-Goals:**

- A health column in the Queues grid. It would require a per-row component in a
  virtualised grid, fed by a new kernel slot; the ranked screen answers the same question
  better. Recorded with its upgrade path under Decision 4.
- A configurable rule DSL for the ladder. See Decision 2.
- Per-consumer attribution beyond the broker's own notification. `listAllConsumersAsJSON`
  carries no per-consumer acknowledgement counter — the limit `SlowConsumerCondition`
  already states.

## Decisions

### 1. Extend `feature/triage` rather than add `feature/consumer-health`

A new module would have to re-declare triage's entire dependency edge set and would split
a concept this codebase has already named and described. Triage is defined as "cross-feature
triage" and is the only module already permitted to read resources, metrics, events and
alerts together.

The verdict lands in `ConsumerHealthService` in the module root; `TriageMcpTools` and the
new `web/TriageController` both become adapters over it. Triage gains its first REST
surface and its first frontend feature, keeping its backend and frontend module ids equal
as ADR-0069 requires.

*Alternatives considered.* A new feature module — rejected as duplicated edges for no
isolation gain. Putting the verdict in `platform/` so `feature/queues` could read it
directly — rejected: the verdict is a product capability that must be disableable, and
platform is what features build on, not where features live.

*Consequence.* `triage/package-info.java` gains `platform.scrape` and `kernel.core`.
`ModularityTest` and `BoundaryRulesTest` are the gate on this decision.

### 2. A fixed ordered ladder, not a rules engine

The verdict is a first-match-wins ladder (see `specs/consumer-health/spec.md`). It is
deliberately not operator-configurable.

The value here is a *shared vocabulary*: `STALLED` must mean the same thing on the screen,
in an alert and in an agent's answer, and each verdict must map to one action. A
configurable predicate set would make every verdict site-specific, which destroys the one
property that makes the feature useful under pressure — and there is no evidence yet of
what operators would want to tune. Three numeric thresholds are exposed
(`idle-ack-rate`, `min-backlog`, and the window), which is where real variation lives.

*Alternative considered.* Expressing the ladder as alert rules and deriving the screen
from firings — rejected: it inverts the dependency (the screen would need alerting
enabled), and a ladder is ordered while rules are independent, so two rules could both be
"true" with no defined precedence.

### 3. `deliveringCount` is the root-cause discriminator

`STALLED` and `STARVED` share three of four signals — consumers attached, backlog, ack
rate at zero. Only messages-in-flight separates them, and they lead to opposite actions
(inspect the consumer process versus inspect the selector). Without it the feature can say
"something is wrong" but not "what", which is precisely the gap ADR-0044 describes.

The verdict needs only the *current* value, which `queue_snapshot` already has. The
history is sampled anyway, alongside `messagesExpired`, because both come free from the
`QueueRow` the sweep already returns and both are needed to chart in-flight and expiry
trends — the natural next question after a `STALLED` verdict.

*Cost.* `metric_sample` grows from four rows to six per queue per tick, +50%, within the
existing 7-day retention and daily partitions. **No broker cost**: no additional request
is issued. Accepted deliberately; the alternative (current value only) was considered and
rejected because the in-flight trend is what distinguishes a consumer that stalled just
now from one that has been stalled for an hour.

### 4. The ranked screen is the view; the Queues grid is untouched

Placement was weighed three ways.

The operator's question is "which queues have consumer trouble", not "show me queues". A
column in the Queues grid answers it only after sorting 900 rows, and `feature/queues`
cannot import `feature/triage`, so the column would need a new kernel slot
(`queue.row.marks`) rendering a component per row in a virtualised grid — real kernel
surface and real render cost for a worse answer.

So: a ranked worst-first screen under *Observe* (`order: 18`, between Flow at 15 and
Metrics at 20), plus a panel in the queue detail drawer through the **existing**
`queue.detail.panels` slot, which `feature/metrics` already contributes to. The panel
sorts above the metrics history panel and links to `/metrics?subject=<queue>` for history
rather than redrawing charts that already exist.

*Upgrade path, if the column is later wanted:* add a `queue.row.marks` kernel slot
mirroring the existing `topology.node.marks`, and have the per-row component read the
cluster-wide health query already in the TanStack Query cache, so 200 rows share one fetch.

*Colour.* Verdicts are words. `--as-danger` on `STALLED`/`NO_CONSUMERS`/`BROKER_SLOW` and
`--as-warning` on `FALLING_BEHIND`, as redundant emphasis only. No new tokens; the
existing semantic set covers it, and a healthy cluster renders near-monochrome.

### 5. Depth trend as a regression slope, computed in Postgres

The existing string trend cannot distinguish a queue oscillating around a mean from one
climbing steadily, because it reads only the first and last point. `MetricSamples` gains
one read using Postgres' native `regr_slope(value, extract(epoch from ts))`, per node then
summed, with the same `GREATEST(..., 0)` restart discipline as the existing rate reads.

Native aggregate over hand-rolled regression: no new code path to test, no rows pulled
into the JVM, and it matches how every other read in that class is written.

### 6. The alert condition lives in `triage`, and `alerting` discovers it

The obvious placement — a `HealthVerdictCondition` in `feature/alerting` — is **not
available**: `feature/triage` already declares a dependency on `feature.alerting`, so
`alerting` reaching into `triage` for the verdict would close a cycle, which Spring
Modulith rejects and `ModularityTest` would fail on.

Inverting the existing edge is worse: triage is the cross-feature reader by definition,
and alerting has no business knowing about diagnosis.

So the condition lives in `feature/triage` beside the service it evaluates, and
`feature/alerting` is made able to accept a condition it does not import:

- `AlertCondition` gains `boolean supports(String metric)`, promoting the `static
  supports` each implementation already has to an interface method.
- `AlertEvaluator` injects `List<AlertCondition>` instead of four named fields, and
  `conditionFor` selects the first whose `supports` matches, ordered so derived metrics are
  tested before raw ones (`@Order`, preserving today's precedence where
  `SlowConsumerCondition` is checked ahead of gauge and rate).

This is a small, principled refactor of an extension point that already exists in all but
name, and it leaves `alerting` importing nothing new — any module already permitted to
depend on `alerting` can now contribute a rule kind.

Everything else is unchanged: the verdict maps to its severity rank as the condition's
`Double`, so `Comparators`, the threshold column, debounce and resolution work untouched,
and `alert_rule.metric` is free text, so there is **no migration** — exactly as
`SlowConsumerCondition` established.

The numeric rank is an internal encoding. The rule form renders a **severity select**, so
no operator ever types or reads a bare rank.

`INSUFFICIENT_DATA` queues are excluded from `universe` entirely rather than reported
inactive — a subject present in `universe` but absent from `active` *resolves* a firing,
and a queue that stopped being sampled must not resolve a stall that is still happening.
This mirrors how the existing conditions treat a subject with fewer than two samples.

## Risks / Trade-offs

- **`metric_sample` grows 50%** → Bounded by the unchanged 7-day retention and daily
  partitioning; insert-tuned, append-only, BRIN-indexed on `ts`. No new index is added.
  Retention is already runtime-tunable if an installation needs to trade history for space.
- **A verdict could contradict the broker's own view** → ADR-0044's precedence is encoded
  as ladder position: `BROKER_SLOW` sits above every derived consumer verdict, and a
  derived verdict states that it is derived.
- **Threshold defaults will be wrong for some brokers** → `idle-ack-rate` and
  `min-backlog` are settings with defaults, and every verdict shows the numbers it was
  computed from, so a wrong verdict is diagnosable rather than mysterious.
- **The ranked query scans every queue in a cluster** → It reads only `queue_snapshot`
  (indexed on `cluster_id`) and pre-aggregated `metric_sample` windows — one query per
  metric per request, not per queue, the same shape `RateCondition` uses per tick. Paged
  through the existing `ResourceQuery`/`PagedView`.
- **Triage gains a `platform.scrape` edge** → Narrow and downward, into the same platform
  module `feature/metrics` and `feature/alerting` already depend on. Enforced by test.
- **`AlertEvaluator`'s dispatch is refactored to a collection** → It touches a path every
  alert rule evaluates. Mitigated by keeping the selection order explicit rather than
  incidental, and by the existing condition tests, which must pass unchanged; the four
  current conditions keep their behaviour and only their wiring changes.
- **Removing the MCP tool's inline logic changes `diagnose`'s output shape** → Intended
  and specified; the tool is pre-1.0 and the replacement is strictly more structured.

## Migration Plan

No data migration. No changeset — `metric_sample.metric` and `alert_rule.metric` are text.

Deployment is ordered so nothing renders an empty verdict: the two new metrics begin
sampling on first boot, and until a queue has two samples in the window its verdict is
`INSUFFICIENT_DATA`, which the spec requires to be stated rather than shown as healthy. In
practice the screen is fully populated after two tier-B intervals.

Rollback is disabling the `triage` feature flag, which removes the screen, the API and the
MCP tool. The two extra sampled metrics are inert if unread; removing them too is a revert
of `MetricSampleWriter`, and existing rows age out under normal retention.
