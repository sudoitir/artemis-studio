# ADR-0044: Slow-consumer detection has two authorities, and the broker wins

- **Status**: accepted
- **Date**: 2026-09-05
- **Deciders**: Mahdi Amirabdollahi

## Context

A consumer that is attached but not draining is one of the most common Artemis
production failures, and it is invisible in every view Studio had: the queue grid
shows a depth and a consumer count, both of which look healthy in exactly this
case. Studio collects every input needed to spot it — `messageCount`,
`consumerCount` and the `messagesAcked` counter, per node, on tier B — and watched
none of them.

Artemis also detects this itself. `slow-consumer-threshold`,
`slow-consumer-check-period` and `slow-consumer-policy` (`NOTIFY` | `KILL`) are
per-address-setting, and when the threshold is breached the broker emits
`CONSUMER_SLOW` on `activemq.notifications` carrying `_AMQ_ConsumerName`. Studio
already subscribes to that address (ADR-0026).

The two are not equivalent, and the difference is not a matter of taste:

- The broker measures **each consumer's own** delivery rate. Studio cannot:
  `listAllConsumersAsJSON` carries no per-consumer acknowledgement counter, so the
  finest grain Studio can reach is a queue on a node.
- Studio can watch a broker that has native detection switched off, which is the
  default.

The slice-0 surface check (`docs/broker-management-notes.md` §14) settled a third
fact that shapes the design: **`getAddressSettingsAsJSON` does not return
`slowConsumerThreshold` at all.** Against Artemis 2.44 it returns 18 fields and the
only slow-consumer one is `slowConsumerThresholdMeasurementUnit`. Studio therefore
cannot see whether native detection is configured.

## Decision

**We will detect slow consumers from two authorities, layered, with the broker
authoritative whenever it is configured.**

1. **The broker's verdict.** `CONSUMER_SLOW` is normalised like any other
   notification and routed to the realtime stream's `consumers` topic. It keeps
   the consumer identity the notification carries, so it attributes to a *named
   consumer*. When the broker and Studio disagree, the broker's verdict is the one
   shown as authoritative.

2. **Studio's derivation**, for brokers where native detection is off: a new
   `ackRatePerConsumer` metric, evaluated by `SlowConsumerCondition` as an ordinary
   threshold rule. Its subject universe is not "every queue" but the queues that
   satisfy **all** of: at least one consumer attached, a non-zero backlog, and not
   paused. Value is the `messagesAcked` rate over the existing 2×tier-B window,
   divided by the consumer count.

3. **Native detection state is reported three-state, and is normally UNKNOWN.**
   Because the threshold is not exposed, Studio reports `slowConsumerDetection` as
   UNKNOWN — never "off" — and ships the enabling `broker.xml` snippet.

4. **No rule is seeded.** A prefilled template is offered in the rule form instead.

## Consequences

- The rule that fires is the one an operator would actually want paged for. The
  triple is the whole point: `messagesAcked < X` alone pages on every quiet queue
  at 3am, a queue with no consumers is not a slow consumer, and a queue with no
  backlog and no acknowledgements is idle.
- **A `paused` column is added to `queue_snapshot`** (changeset 015). A paused queue
  satisfies the triple and is operationally expected. `paused` is already on every
  `listQueues` row Studio reads, so this costs no extra broker call — but it is a
  schema change, and `005-broker-cache.sql` is released, so it is a new changeset.
- **No alerting migration.** `AlertEvaluator.conditionFor` dispatches on free-text
  `alert_rule.metric`, so a derived metric needs no CHECK-constraint edit.
- **No `broker_event` migration either**: `010-broker-events.sql` declares
  `type TEXT` with no CHECK, so `CONSUMER_SLOW` is accepted as-is.
- The rate is reused from `MetricSeriesRepository.latestRateBySubject`, not
  reimplemented, so ADR-0033's `GREATEST(…, 0)` clamp applies and a broker restart
  resetting the monotonic counter cannot produce a spurious firing.
- **Studio's derivation cannot name a consumer, and says so** — in the rule form
  and on the firing. Stating the limit is the honest alternative to implying a
  precision the data does not carry (non-negotiable #5).
- The rate `metric_sample` records is per queue name across the cluster, not per
  node. In an HA pair only one endpoint serves a queue, so this is the serving
  node's rate; on a symmetric cluster where one queue name is served on several
  nodes, a node-scoped rule divides a cluster-wide rate by that node's consumer
  count. Revisit if node-grained rates are ever recorded.
- When Artemis begins exposing `slowConsumerThreshold`, the capability answer
  improves with no change outside `CapabilityProbe` — the AVAILABLE and UNAVAILABLE
  branches are already written.

## Alternatives considered

- **Only surface the broker's own detection.** Simplest, and useless on the default
  configuration, where the threshold is unset and no notification is ever emitted.
- **Only derive it in Studio.** Discards the one source that can name a consumer,
  and would have Studio contradicting a broker that has already decided.
- **A plain `messagesAcked` rate rule, no new condition.** Already possible today,
  and the reason nobody uses it: with no backlog and no consumer-count guard it
  fires on every idle queue.
- **Report native detection as "off" when the threshold is absent.** Rejected: it
  reports a difference Studio cannot observe. UNKNOWN is the true answer.
- **Document that paused queues will fire, instead of adding the column.** Cheaper
  by one changeset, and it makes the first firing an operator sees a false positive
  — which is how a rule loses its audience.
