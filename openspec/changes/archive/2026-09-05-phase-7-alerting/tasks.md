## 1. Schema

- [x] 1.1 Add Liquibase changeset `013-alerting.sql` + `<include>` in
      `db.changelog-master.xml`. Do not edit `006-alerting.sql`.
- [x] 1.2 `013-alert-rule-kind`: add `name`, `state_condition`, `created_at`,
      `updated_at`, `kind` (default `METRIC_THRESHOLD`) to `alert_rule`; relax
      `metric`/`comparator`/`threshold` to nullable; add the kind-shape CHECK and
      the `state_condition` CHECK (`SPLIT_BRAIN|NODE_DOWN|REPLICATION_BEHIND|CLUSTER_DEGRADED`).
- [x] 1.3 `013-alert-rule-index`: `(cluster_id, kind) WHERE enabled`.
- [x] 1.4 `013-alert-rule-channel`: join table `(rule_id, channel_id)` PK, both FKs
      cascade.
- [x] 1.5 `013-alert-firing`: append-only table (`seq` identity PK, `started_at`,
      `resolved_at`, `value`, `severity`, `subject_key`, `rule_id`, `cluster_id`);
      index `(cluster_id, seq DESC)`; partial index
      `(cluster_id) WHERE resolved_at IS NULL`; insert-tuned autovacuum,
      `fillfactor = 100`.
- [x] 1.6 `013-alert-delivery`: `seq` identity PK, `created_at`, `next_attempt_at`,
      `delivered_at`, `attempts`, `state` (`PENDING|SENT|FAILED|DEAD`),
      `last_error`, `payload JSONB`, `rule_id`, `channel_id`; partial index
      `(next_attempt_at) WHERE state = 'PENDING'`; `fillfactor = 80` + aggressive
      autovacuum like `alert_state`.
- [x] 1.7 `013-notification-channel-secret`: add `secret_ct BYTEA`,
      `secret_nonce BYTEA`, `enabled BOOLEAN NOT NULL DEFAULT TRUE`,
      `UNIQUE (name)`; drop `EMAIL` from the kind CHECK.
- [x] 1.8 `013-builtin-rules-backfill`: seed `SPLIT_BRAIN`/`NODE_DOWN`/
      `REPLICATION_BEHIND` rules for every existing cluster.
- [x] 1.9 Integration test (`AlertSchemaIntegrationTest` on
      `support/PostgresIntegrationTest`): `013` applies cleanly on top of `006`,
      the kind-shape CHECK rejects a mixed-shape row, the channel name is
      unique, and the dispatcher's `FOR UPDATE SKIP LOCKED` claim query returns
      only due rows.

## 2. Domain: conditions and state machine

- [x] 2.1 `domain/alerting/AlertCondition.java` interface — returns an
      `Evaluation(universe, active)` per rule so the evaluator can resolve a
      vanished subject, not just a bare active map.
- [x] 2.2 `domain/alerting/GaugeCondition.java` — reads `queue_snapshot` for
      `messageCount`/`consumerCount`/`deliveringCount`/`scheduledCount`, applies
      `scope` (addressPattern/queuePattern/node).
- [x] 2.3 `persist/MetricSeriesRepository`: added
      `Map<String, Double> latestRateBySubject(clusterId, metric, from, to)` —
      `GREATEST(max-min,0)/windowSeconds` grouped by `subject_name`; omits subjects
      with fewer than two samples in the window.
- [x] 2.4 `domain/alerting/RateCondition.java` — calls `latestRateBySubject` for
      `messagesAdded`/`messagesAcked` over a 2×tier-B window.
- [x] 2.5 `domain/alerting/StateCondition.java` — reads `HaStateEvaluator`/
      `SplitBrainRegistry`/`broker_node` for the four closed state conditions;
      split-brain fires only on `SplitBrainStatus.CRITICAL`.
- [x] 2.6 `domain/alerting/AlertStateMachine.java` (pure, no Spring) — the
      OK→PENDING→FIRING→resolved transition table from design.md decision 4,
      including the vanished-subject-resolves rule (handled by the caller,
      `AlertEvaluator`, using the state machine's per-subject `advance`).
- [x] 2.7 Unit test `AlertStateMachineTest`, table-driven like
      `FlowStateMachineTest`: duration debounce, reset-on-false, zero-duration
      immediate fire, vanished subject resolves (covered via `AlertEvaluatorIntegrationTest`).
- [x] 2.8 Unit test `MetricSeriesRepositoryTest#latestRateBySubject`: a counter
      reset never goes negative (the true never-negative guarantee of
      `GREATEST(max-min,0)` — see the note on task 2.3/design.md); under-two-samples
      subject is omitted.

## 3. Evaluation wiring

- [x] 3.1 `service/AlertEvaluator.java` — `evaluate(clusterId, kind)`: loads
      enabled rules of that kind for the cluster, runs the matching condition,
      feeds `AlertStateMachine`, persists `alert_state`/`alert_firing` transitions,
      writes one `alert_delivery` row per `(rule, channel)` for the tick's
      transitions, and publishes the `alerts` SSE signal only when a transition
      occurred.
- [x] 3.2 Wired `ScrapeScheduler.tierA` to call
      `alertEvaluator.evaluate(clusterId, "STATE")` after
      `streamSignals.afterTierA(...)`.
- [x] 3.3 Wired `ScrapeScheduler.tierB`/`tierC` to call
      `alertEvaluator.evaluate(clusterId, "METRIC_THRESHOLD")` after their
      per-cluster fan-out.
- [x] 3.4 Confirmed no evaluation path opens a transaction spanning broker I/O
      (ADR-0015) — evaluation reads only already-persisted state.
- [x] 3.5 Integration test (`AlertEvaluatorIntegrationTest`): a seeded threshold
      rule fires after a debounce-free tick against real `queue_snapshot` rows
      and resolves when the value clears; a disabled rule never evaluates.

## 4. Delivery and channels

- [x] 4.1 Backoff timing for the dispatcher: reimplemented stateless as
      `scheduler/AlertBackoff` rather than reusing `broker.core.Backoff`
      directly (that class is stateful, keyed per node, and package-private —
      see ADR-0036's alternatives-considered; a delivery's attempt count lives
      on the row, not an in-memory map).
- [x] 4.2 `scheduler/AlertDispatcher.java` — `@Scheduled`, claims `PENDING` rows
      via `SELECT ... FOR UPDATE SKIP LOCKED`, dispatches, records outcome,
      backs off on failure, caps at `artemis-studio.alerting.max-attempts`
      (default 5) then `DEAD`.
- [x] 4.3 `security/SecretVault` — added an overload taking an opaque AAD string
      (alongside the existing cluster-bound one); channels call it with
      `channelId + "|" + kind`.
- [x] 4.4 Notification sender package (`broker/notify/`) — `NotificationSender`
      interface, `SlackSender` (Block Kit `{text, blocks}` POST; `404 no_team` →
      `DEAD` immediately; `429` honors `Retry-After`), `WebhookSender` +
      `WebhookSigner` (Standard Webhooks: `webhook-id`/`webhook-timestamp`/
      `webhook-signature: v1,<base64 HMAC-SHA256>` over `{id}.{timestamp}.{body}`,
      `javax.crypto.Mac`).
- [x] 4.5 Second `RestClient` bean (`NotificationHttpConfig`) built with the
      `BrokerClientFactory` recipe (own `HttpClientSettings` timeouts), used
      only for outbound notification delivery — no sharing with the per-node
      rate limiter or broker TLS bundles.
- [x] 4.6 `Alerting` nested record in `ArtemisStudioProperties` (dispatch
      interval, max attempts, HTTP timeout, backoff bounds) + `application.yml`
      block.
- [x] 4.7 Unit test `WebhookSignerTest`: verified against the Standard Webhooks
      spec's documented `"{id}.{timestamp}.{body}"` construction and secret
      serialization (base64, optional `whsec_` prefix), cross-checked against an
      independently computed HMAC-SHA256 in the test itself — ctx7 returned no
      single official fixed numeric vector to pin to directly.
- [x] 4.8 Unit test `AlertDispatcherTest`: a 5xx backs off and retries; a
      permanent-failure result (e.g. Slack `404 no_team`) goes `DEAD` without
      retry; a `429`-style result honors its `retryAfter`; reaching max attempts
      ends `DEAD`.

## 5. Persistence and CRUD API

- [x] 5.1 JPA entities: `AlertRuleEntity`, `AlertStateEntity`, `AlertFiringEntity`,
      `AlertDeliveryEntity`, `NotificationChannelEntity`, `AlertRuleChannelEntity` —
      `@Getter @Setter @NoArgsConstructor(PROTECTED)
      @EqualsAndHashCode(onlyExplicitlyIncluded)`, explicit constructors, never
      `@Data`.
- [x] 5.2 Spring Data repositories for each. Hand-written `AlertViewMapper`
      instead of MapStruct — `AlertRuleView` aggregates a rule's bound channel
      ids alongside its own columns, which a generated one-entity mapper cannot
      do (the same escape hatch `QueueViewMapper` already uses).
- [x] 5.3 `service/AlertRuleService` — CRUD + rule↔channel binding, audited via
      `AuditService.begin/succeed/fail` in the same transaction (ADR-0023
      actor resolution).
- [x] 5.4 `service/NotificationChannelService` — CRUD, `SecretVault` encrypt on
      write, masked on read, "send test" action (audited, uses the real sender).
- [x] 5.5 `web/dto/AlertViews.java` — `final class` + private ctor holding nested
      records, `@Schema(requiredMode=REQUIRED/nullable=true)` on every component.
- [x] 5.6 `web/AlertsController` — `/api/v1/clusters/{id}/alerts/firing`,
      `/history`, and `/rules` (CRUD).
- [x] 5.7 `web/NotificationChannelsController` — `/api/v1/channels` (CRUD + test).
- [x] 5.8 `web/AlertSummaryController` — `GET /api/v1/alerts/firing` cross-cluster
      firing counts, for the shell badge.
- [x] 5.9 `ClusterService.register` — seeds the three built-in `STATE` rules for
      a newly registered cluster.
- [x] 5.10 `./mvnw test` regenerated `web/openapi.json` via `OpenApiSnapshotTest`.
- [x] 5.11 Controller integration tests (`MockMvc`, `PostgresIntegrationTest` base):
      `AlertsControllerTest` (rule CRUD, audit rows, kind-shape validation) and
      `NotificationChannelsControllerTest` (channel CRUD, secret never appears
      in any response body, unknown kind rejected).

## 6. SSE

- [x] 6.1 Added `alerts` to `StreamController.KNOWN_TOPICS`.
- [x] 6.2 `AlertEvaluator` publishes the `alerts` signal directly via `SseHub`
      only on an actual firing/resolve transition for the cluster (no
      coalescing needed — evaluation itself only signals on real change).
- [x] 6.3 Added `alerts` to the frontend `Topic` union in `api/stream.ts` in the
      same change, invalidating both the per-cluster and global firing-count
      query keys.

## 7. Frontend

- [x] 7.0 Loaded the `frontend-development-guide` and `ui-ux-pro-max` skills
      before this section (the latter is scoped to mobile/React Native in this
      repo's skill set; applied its universally-relevant rules — accessible
      names, color-not-alone, confirmation on destructive actions — through the
      project's actual Mantine conventions rather than its RN-specific checklist).
- [x] 7.1 `npm --prefix web run gen:api` regenerated `schema.d.ts` from the
      updated OpenAPI snapshot.
- [x] 7.2 `web/src/api/client.ts` — DTO aliases, `keys.alertRules/alertFiring/
      alertHistory/channels/firingCounts`, `useAlertRules`, `useCreate/Update/
      DeleteAlertRule`, `useFiringAlerts`, `useAlertHistory`, `useFiringCounts`
      (cross-cluster), `useNotificationChannels` + CRUD + `useTestNotificationChannel`.
- [x] 7.3 Added the `alerts` route (`clusters/$clusterId/alerts`) to `router.tsx`
      (`validateSearch` for the `tab` param, `errorComponent: RouteError`) and a
      nav entry in `app/navItems.ts`.
- [x] 7.4 `web/src/alerts/AlertsView.tsx` — tabbed firing/history/rules screen,
      split into `FiringPanel.tsx`, `HistoryPanel.tsx`, `RulesPanel.tsx`.
- [x] 7.5 `web/src/alerts/RuleForm.tsx` — kind switch (threshold vs. state
      condition), gauge/rate-flagged metric select, duration, severity,
      channel multi-select. `scope` (address/queue pattern, node) is not yet a
      structured sub-form — deferred, noted below.
- [x] 7.6 `web/src/settings/NotificationChannels.tsx` — channel CRUD, write-only
      masked secret field, "send test" button, wired into `SettingsView.tsx`.
- [x] 7.7 Wired firing state into `topology/layout.ts` + `TopologyCanvas.tsx` to
      render the reserved `--as-alert-dot` indicator on affected nodes, sourced
      from `useFiringAlerts` in `TopologyGraph.tsx`.
- [x] 7.8 Shell firing badge in `RootLayout` (via `useFiringCounts`, 30s
      `refetchInterval`, also invalidated by the per-cluster `alerts` SSE
      signal) + a per-cluster count badge on the Alerts `NavItem` in
      `ClusterViewNav.tsx`.
- [x] 7.9 Frontend tests (MSW + `renderWithProviders`): `RulesPanel.test.tsx`
      (list, empty state, gauge/rate switch, create), `NotificationChannels.test.tsx`
      (list, secret never rendered, empty state, create), `ClusterViewNav.test.tsx`
      (badge count shown/hidden per cluster).

**Scope note surfaced during apply**: `RuleForm` does not yet expose
`scope.addressPattern`/`queuePattern`/`node` as form fields — a rule created
through the UI today is always cluster-wide. The backend fully supports scope
(`AlertScope`, `GaugeCondition`/`RateCondition` apply it), and the API accepts
raw `scope` JSON, so this is a UI gap, not a backend one. Flagging rather than
silently shipping it as if scoped rules were reachable from the screen.

## 8. Docs

- [x] 8.1 Wrote ADR-0035 (rule model + evaluation timing).
- [x] 8.2 Wrote ADR-0036 (delivery queue + channel signing + audit boundary).
- [x] 8.3 Updated `docs/roadmap.md` Phase 7 Notes to cite ADR-0035/0036; updated
      `docs/adr/README.md`'s index (which had also been missing 0033/0034).
- [x] 8.4 Corrected `docs/architecture.md`'s reserved `alerting/` package note
      to the project's actual layered structure (`domain/alerting`,
      `service/AlertEvaluator`).
- [x] 8.5 Updated `README.md` Phase 7 TODO checkboxes and `openspec/project.md`'s
      current-phase summary (which had not been updated since Phase 5, either).

## 9. Verification

- [x] 9.1 `./mvnw verify` — Spotless, Liquibase vs. Testcontainers PG, full test
      suite: 205 backend tests pass.
- [x] 9.2 `just verify` — `verify-api` + `verify-web`: passes end to end
      (frontend build, lint, and 48 Vitest tests all green).
- [ ] 9.3 End-to-end against `just up` (real compose stack, real brokers): NOT
      run in this session — would need the full Docker Compose broker pair up
      and a manual send/observe/webhook-receiver loop. Left for a manual pass;
      the equivalent logic is covered by `AlertEvaluatorIntegrationTest` and
      `AlertDispatcherTest` against Testcontainers/mocks respectively.
- [ ] 9.4 Split-brain end-to-end against real brokers: NOT run in this session
      for the same reason as 9.3. The split-brain-only-fires-on-CRITICAL logic
      itself is exercised by `StateCondition` reading the same
      `HaStateEvaluator`/`SplitBrainRegistry` ADR-0012 already unit-tests
      elsewhere; what's unverified here is the live end-to-end wiring through a
      real failover.
