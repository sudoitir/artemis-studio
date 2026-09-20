# ADR-0089: Consumer health is one ordered verdict ladder, shared by every surface

- **Status**: accepted
- **Date**: 2026-09-20
- **Deciders**: Mahdi Amirabdollahi

## Context

A consumer that is attached but not draining is the most common Artemis production
failure. [ADR-0044](0044-slow-consumer-detection-two-authorities.md) recorded why
Studio is blind to it: the queue grid "shows a depth and a consumer count, both of which
look healthy in exactly this case", and gave Studio a derived slow-consumer *alert*.

That left two gaps. An operator looking at a rising backlog still has no screen that says
*why* it is rising, and no way to ask which of a cluster's queues have consumer trouble
right now. And the answer, where one exists, is inconsistent: `feature/triage`'s
`diagnose` MCP tool computes a depth trend as first-versus-last ±10% returned as a
sentence, and a slow-consumer verdict from a magic ×10 ratio, while
`feature/alerting`'s `SlowConsumerCondition` computes a different, better answer from the
same data. A console, an alert and an agent could each describe the same queue
differently.

The inputs are all collected already: `queue_snapshot` carries depth, consumers,
delivering, scheduled and paused per node; `metric_sample` carries the counters that
`MetricSamples.latestRateWithTimeBySubject` turns into restart-clamped per-node rates.
What is missing is a verdict over them, and one place that owns it.

## Decision

**We will classify each queue into exactly one consumer-health verdict, drawn from a
fixed ordered ladder, computed once in `feature/triage` and served unchanged to the
screen, the REST API, the MCP tool and the alert condition.**

The ladder is evaluated first-match-wins:

| Verdict | Condition |
| --- | --- |
| `INSUFFICIENT_DATA` | fewer than two samples in the window — no rate is computable |
| `PAUSED` | paused on any node |
| `NO_CONSUMERS` | no consumers attached, depth above zero |
| `BROKER_SLOW` | the broker itself reported a slow consumer |
| `STALLED` | consumers attached, backlog, ack rate at or below idle, **delivering above zero** |
| `STARVED` | consumers attached, backlog, ack rate at or below idle, **delivering zero** |
| `FALLING_BEHIND` | acking, but enqueue exceeds ack and depth is climbing |
| `DRAINING` | ack exceeds enqueue, depth above zero |
| `HEALTHY` | none of the above |

Four properties are part of the decision, not incidental to it.

**The ladder is fixed, not operator-configurable.** Only three numeric thresholds are
settings: the idle acknowledgement rate, the minimum backlog, and the evaluation window.

**`deliveringCount` is what separates `STALLED` from `STARVED`.** The two share three of
four signals and lead to opposite actions — inspect the consumer process, or inspect the
selector. To make that distinction, and to chart it afterwards, `MetricSampleWriter` also
samples `deliveringCount` and `messagesExpired`, which the sweep already returns.

**The broker keeps the last word**, per ADR-0044: `BROKER_SLOW` sits above every derived
consumer verdict, and a derived verdict states that it is derived.

**An uncomputable verdict is never healthy.** `INSUFFICIENT_DATA` is reported and ranked
apart, and a rate that could not be computed is never rendered as zero.

## Consequences

One vocabulary. `STALLED` means the same thing on the screen, in an alert and in an
agent's answer, because there is one evaluation behind all four. The MCP tool's inline
trend and slow-consumer heuristics are deleted, which finally makes `TriageMcpTools` the
thin adapter its own doc comment already claimed it was.

`feature/triage` gains its first REST controller and its first frontend feature, and a
`platform.scrape` dependency — narrow, downward, into the module `feature/metrics` and
`feature/alerting` already depend on.

**The alert condition cannot live in `feature/alerting`.** Triage already depends on
alerting, so the reverse edge would close a cycle Spring Modulith rejects. Instead
`AlertCondition` gains `supports(String)` and `AlertEvaluator` injects
`List<AlertCondition>`, discovering conditions by bean collection. The condition lives in
triage. Any module already allowed to depend on alerting can now contribute a rule kind —
a real extension point where there was hard-wiring.

`metric_sample` grows by half, from four rows per queue per sweep to six, inside the
unchanged 7-day retention and daily partitions. **No broker call is added**, because both
new values come from the `QueueRow` the sweep already fetched. No table and no changeset
are added either: `metric_sample.metric` and `alert_rule.metric` are `text`.

What we are committed to: the ladder's order is now a compatibility surface. Reordering
it, or changing what a verdict means, changes what an alert fires on and what an agent
reports, and needs a superseding ADR rather than an edit.

What will need revisiting: the thresholds are defaults chosen ahead of field evidence.
Every verdict ships the numbers it was computed from precisely so a wrong one is
diagnosable. Per-consumer attribution remains impossible beyond the broker's own
notification, because `listAllConsumersAsJSON` carries no per-consumer acknowledgement
counter — the limit ADR-0044 already recorded.

## Alternatives considered

**A configurable rule set instead of a fixed ladder.** Rejected: the value is a shared
vocabulary, and site-specific predicates destroy it. Rules are also unordered, so two
could be true at once with no defined precedence, where the ladder's whole job is to pick
one. There is no evidence yet about what operators would tune; the three numeric
thresholds cover where real variation lives.

**Deriving the screen from alert firings.** Rejected: it inverts the dependency — the
screen would require alerting to be enabled — and firings are debounced, so the screen
would lag the truth it is displaying.

**A new `feature/consumer-health` module.** Rejected: it would re-declare triage's entire
dependency edge set for no isolation gain, and split a concept this codebase had already
named. Triage is defined as the cross-feature diagnosis module.

**Putting the verdict in `platform/`** so `feature/queues` could read it directly and show
a column. Rejected: the verdict is a product capability that must be disableable, and
platform is what features build on, not where features live.

**A health column in the Queues grid.** Rejected for now: `feature/queues` cannot import
`feature/triage`, so it would need a new `queue.row.marks` kernel slot rendering a
component per row in a virtualised grid — real kernel surface and real render cost to
answer "which queues are unhealthy" worse than a ranked screen does. The slot remains the
upgrade path if the column is later wanted.

**Sampling only the current `deliveringCount` and no history.** Rejected: it distinguishes
`STALLED` from `STARVED` but cannot tell a consumer that stalled a minute ago from one
that has been stalled for an hour, which is the next question the verdict provokes.
