## Why

A consumer that is attached but not draining is the most common Artemis production
failure, and ADR-0044 already states why Studio cannot see it: the queue grid "shows a
depth and a consumer count, both of which look healthy in exactly this case." An
operator holding a rising backlog today has no screen that answers *why* it is rising,
and no way at all to ask "which of my queues have consumer trouble right now?"

The data to answer both has been collected since Phase 6. What is missing is a verdict
over it, and somewhere to read that verdict. This is roadmap item **A · Consumer
health**.

## What Changes

- **A Consumer health screen** per cluster, under *Observe*: every queue ranked
  worst-first by a verdict, with the evidence behind it and the next action. The
  operator's question is "who is unhealthy", so the ranking is the view — not a chart
  they must already know which queue to open.
- **A verdict panel in the queue detail drawer**, through the existing
  `queue.detail.panels` slot, beside the depth and throughput history already there.
- **One verdict, computed once.** An ordered ladder — `PAUSED`, `NO_CONSUMERS`,
  `BROKER_SLOW`, `STALLED`, `STARVED`, `FALLING_BEHIND`, `DRAINING`, `HEALTHY`,
  `INSUFFICIENT_DATA` — served to the screen, the REST API and the `diagnose` MCP tool
  from a single service, so the console and the model can never disagree during an
  incident.
- **Root cause, not just a label.** `STALLED` (consumers hold messages and acknowledge
  none) and `STARVED` (attached, nothing dispatched — a selector or filter mismatch)
  are distinguished by `deliveringCount`, and lead to opposite actions. Each verdict
  carries depth, depth slope, add/ack/net rate, per-consumer velocity, in-flight count,
  drain ETA, and the age and span of the samples it rests on.
- **The broker keeps the last word.** A `CONSUMER_SLOW` notification outranks any
  derived verdict and names the consumer, per ADR-0044. Studio's ladder is the fallback
  for brokers where `slow-consumer-threshold` is not configured.
- **`INSUFFICIENT_DATA` is never rendered as healthy.** Too few samples to compute a
  rate is stated as such; an absent number never reads as zero.
- **Two more sampled metrics**: `deliveringCount` and `messagesExpired` join the four
  already written per queue per sweep. No additional broker call — both are already in
  the sweep's response.
- **A health-verdict alert condition**, so "queue STALLED for 10 minutes" fires through
  the existing rule, state-machine and notification machinery.
- **`diagnose` becomes the thin adapter it already claims to be**: its inline trend and
  slow-consumer heuristics are removed in favour of the shared service.

## Capabilities

### New Capabilities
- `consumer-health`: the verdict ladder and its precedence, the evidence every verdict
  carries, how an uncomputable verdict and a stale or partially-reporting cluster are
  stated, the ranked screen and the drawer panel, and the read API behind them.

### Modified Capabilities
- `metrics`: the sampled metric set gains `deliveringCount` (gauge) and
  `messagesExpired` (counter), queryable as series like the existing four.
- `alerting`: a derived `consumerHealth` condition kind evaluated from the shared
  verdict, alongside the existing `ackRatePerConsumer`.
- `operator-ui`: a verdict is carried in words with colour as redundant emphasis only,
  and an uncomputable verdict is stated rather than omitted or shown as healthy.
- `mcp-server`: `diagnose` reports the same typed verdict and evidence as the screen
  instead of its own prose trend and hedged slow-consumer guess.

## Impact

- **Backend**: `feature/triage` gains `ConsumerHealthService`, its verdict and evidence
  types, properties and its first REST controller; its `package-info.java` gains
  `platform.scrape` and `kernel.core` edges. `platform/scrape/MetricSampleWriter` writes
  two more metrics; `platform/scrape/MetricSamples` gains one depth-slope read;
  `feature/metrics/MetricQueryService` learns the two new metric names;
  `feature/alerting` gains `HealthVerdictCondition`.
- **No new tables and no Liquibase changeset.** The verdict is derived from
  `queue_snapshot` and `metric_sample`, and `metric_sample.metric` is a `text` column,
  so new metric names need no migration. `alert_rule.metric` is likewise free text.
- **API**: `GET /api/v1/clusters/{clusterId}/consumer-health`.
- **Frontend**: new feature `web/src/features/triage/`; a nav entry under *Observe*; a
  `queue.detail.panels` contribution. No new dependency and no new `--as-*` token.
- **Broker load**: unchanged. No new Jolokia call — the two new metrics come from the
  `QueueRow` the existing sweep already returns (non-negotiable #1).
- **Storage**: `metric_sample` grows by 50% (four rows per queue per tick becomes six)
  within the same 7-day retention and daily partitioning.
- **ADRs**: 0089 (the verdict ladder). Depends on 0033, 0035, 0044, 0055, 0069, 0070,
  0074.
- **Not in scope**: a health column in the Queues grid (it would need a
  `queue.row.marks` kernel slot); per-consumer attribution beyond the broker's own
  notification, which `listAllConsumersAsJSON` cannot support; capacity forecasting and
  SLA tracking, both separate roadmap items.
