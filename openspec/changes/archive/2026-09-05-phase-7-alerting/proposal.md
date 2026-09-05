## Why

`docs/roadmap.md` Phase 7 is "Alert rules · evaluation loop · webhook / Slack channels."
Metric collection (Phase 6) and HA state derivation (Phase 1, ADR-0012) both already
run, but nothing watches either for a condition worth paging on. Changeset
`006-alerting.sql` was scaffolded in Phase 1 ("Populated from Phase 7") and applied but
never used; read against what Phase 6 and ADR-0012 actually built, its schema is the
wrong shape — every rule is a metric threshold, but three of the five README tasks
(split-brain, node down, replication desync) are state transitions with no metric row
to compare against, there is no rule→channel routing, no firing history, no delivery
ledger, and channel secrets have no encryption path. This change corrects the schema
(a new changeset — `006` is released and is never edited) and builds the evaluation,
delivery, and UI on top of it.

## What Changes

- Add a discriminated `alert_rule.kind` (`METRIC_THRESHOLD` | `STATE`): threshold rules
  keep `metric`/`comparator`/`threshold`; state rules add `state_condition`
  (`SPLIT_BRAIN` | `NODE_DOWN` | `REPLICATION_BEHIND` | `CLUSTER_DEGRADED`), a closed
  set backed by the existing `HaStateEvaluator` / `SplitBrainRegistry` / `broker_node`
  read models. One evaluator and state machine serve both kinds.
- Threshold rules evaluate gauges (`messageCount`, `consumerCount`, `deliveringCount`,
  `scheduledCount`) from `queue_snapshot`'s latest row, and rates (`messagesAdded`,
  `messagesAcked`) from a new batched `metric_sample` read
  (`latestRateBySubject`) — restart-safe per ADR-0033's clamp, one query per
  `(cluster, rate metric)` per tick regardless of rule count.
- Add `alert_rule.name`/`created_at`/`updated_at`, an `alert_rule` ↔
  `notification_channel` join table, an append-only `alert_firing` history table
  (the OK→PENDING→FIRING→resolved trail `alert_state` alone cannot hold), and a durable
  `alert_delivery` retry queue/ledger (`SELECT ... FOR UPDATE SKIP LOCKED`, exponential
  backoff via the existing `Backoff` component, max 5 attempts then `DEAD`).
- `notification_channel` gains `secret_ct`/`secret_nonce` (AES-GCM, ADR-0009, new AAD
  overload keyed by `channelId|kind`), `enabled`, and a `UNIQUE(name)`; drops `EMAIL`
  from its kind CHECK — no SMTP is built.
- Evaluation runs inline after `ScrapeScheduler`'s tier completions (state rules after
  tier A, threshold rules after tier B/C), not on a new independent timer — this keeps
  alert latency equal to scrape latency and avoids `for_seconds` jitter against an
  unsynchronized clock.
- Delivery batches every transition a rule produces in one tick into a single
  notification per `(rule, channel)` — the storm guard for a rule matching many
  subjects at once.
- Slack (Block Kit `POST`) and generic webhook (Standard Webhooks HMAC signing)
  channels; rule/channel CRUD writes `audit_event` in the same transaction
  (ADR-0023); firings and deliveries are not audited — they are machine-generated and
  have their own tables as record.
- Built-in `SPLIT_BRAIN`/`NODE_DOWN`/`REPLICATION_BEHIND` rules are seeded per cluster
  on registration, ordinary and editable, not hard-coded always-on.
- New SSE `alerts` topic (signal-only, per ADR-0027); an alerts screen, rule/channel
  CRUD UI, the topology graph's already-reserved alert dots, and a cross-cluster
  firing-count badge in the shell (polled — the SSE stream is per-cluster and does not
  change its contract for a global count).
- **BREAKING**: none — `006-alerting.sql`'s tables were never populated or read by any
  shipped code path, so widening/patching them via a new changeset has no consumer to
  migrate.

## Capabilities

### New Capabilities
- `alerting`: rule model (threshold + state conditions), evaluation and `for_seconds`
  debounce semantics, firing history, notification delivery and retry, built-in
  critical alerts, and the alerts/rule/channel UI.

### Modified Capabilities
- `scrape-scheduling`: tier A and tier B/C completion now trigger alert evaluation for
  their respective rule kinds.
- `realtime-stream`: adds the `alerts` topic to `KNOWN_TOPICS` and the frontend `Topic`
  union.
- `metrics`: `MetricSeriesRepository` gains a batched latest-rate-per-subject read used
  by rate-threshold rule evaluation.
- `audit-log`: rule and channel CRUD (including manual "send test") are audited;
  firings and deliveries are explicitly not, to keep the operator audit log from being
  drowned by machine-generated events.

## Impact

- **Backend**: new changeset `013-alerting.sql`; new `domain/alerting/*`
  (`AlertCondition`, `GaugeCondition`, `RateCondition`, `StateCondition`,
  `AlertStateMachine`); `service/AlertEvaluator`, `service/AlertRuleService`,
  `service/NotificationChannelService`; `scheduler/AlertDispatcher`; a notification
  sender package (`SlackSender`, `WebhookSender`, `WebhookSigner`); new
  `persist/Alert{Rule,State,Firing,Delivery}Entity` + repositories,
  `NotificationChannelEntity`; `MetricSeriesRepository.latestRateBySubject`; new
  `web/AlertsController`, `web/AlertSummaryController`, `web/NotificationChannelsController`,
  `web/dto/AlertViews`; `SecretVault` gains an opaque-AAD overload; `ScrapeScheduler`
  gains two call sites; `ClusterService.register` seeds built-in rules;
  `StreamController.KNOWN_TOPICS` gains `alerts`; `web/openapi.json` regenerated.
- **Frontend**: new `web/src/alerts/` (`AlertsView`, `RuleForm`), notification channel
  CRUD in `web/src/settings/`, a new route + nav item, topology alert dots wired up,
  a shell firing badge, `web/src/api/client.ts` additions, `schema.d.ts` regenerated.
- **Docs**: ADR-0035 (rule model + evaluation timing), ADR-0036 (delivery queue +
  channel signing + audit boundary); `docs/roadmap.md` Phase 7 Notes cite both;
  `docs/architecture.md`'s reserved `alerting/` package note corrected to the
  project's actual layered structure.
- **No new dependencies** — `javax.crypto.Mac` for HMAC signing, the existing
  `Backoff` component for retry timing, a second `RestClient` built with the existing
  `BrokerClientFactory` recipe for outbound delivery.
