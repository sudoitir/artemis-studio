## 1. Channel kinds and formatting (ADR-0105)

- [x] 1.1 Add `spring-boot-starter-mail` to `pom.xml`.
- [x] 1.2 Add `feature/alerting/changes/0002-channel-kinds-and-state-conditions.sql`. It widens `ck_notification_channel_kind` to `WEBHOOK`, `SLACK`, `EMAIL`, `TEAMS` and `PAGERDUTY`, and `ck_alert_rule_state_condition` to include `CONFIG_DRIFT` (which the database had never accepted) and `SETUP_RISK`, with a rollback.
- [x] 1.3 Add `AlertMessage` (parsed payload) and `AlertMessageFormatter` (title, plain text, escaped HTML). Move `SlackSender` onto them. Unit tests cover escaping, labels, and a missing optional field.
- [x] 1.4 Payload enrichment in `AlertEvaluator` (design D2): `event`, `version`, cluster id and name, counts, `subjectLabel`, `at`, and `studioUrl` from the new `alerting.public-url` setting. A test shows the old fields are unchanged.
- [x] 1.5 `TeamsSender` (D4), `PagerDutySender` (D3) and `EmailSender` (D5), each with unit tests for body shape and outcome classification. The email test covers CR/LF in the subject and STARTTLS required.
- [x] 1.6 `ChannelConfigValidator`: per-kind validation (D6) that names the field. It runs on create, update and test.

## 2. Channel API

- [x] 2.1 `NotificationChannelView` gains `boundRuleCount` and `health` from one grouped query.
- [x] 2.2 `GET /channels/{id}/deliveries` and `POST /channels/{id}/deliveries/{seq}/retry` (audited; 409 unless DEAD).
- [x] 2.3 `POST /channels/test` (an unsaved configuration, reusing the stored secret) and `POST /channels/{id}/test`. Both return `ChannelTestResultView` with 200, and are audited.
- [x] 2.4 Setting `alerting.email-timeout`, and the `artemis-studio.alerting.public-url` property (`ARTEMIS_STUDIO_PUBLIC_URL`). It is a property, not a runtime setting, because settings have no string kind.
- [x] 2.5 `SETUP_RISK` is added to `AlertRuleService.STATE_CONDITIONS`.

## 3. Setup review module (ADR-0106)

- [x] 3.1 Module skeleton: `feature/setupreview` with `package-info`, `SetupReviewModule` (topic `setup-review`, settings, MCP tool), `SetupReviewFeature`, registration in `StudioFeatures`, and the architecture fixtures. Add `ClusterLock.Scope.SETUP_REVIEW`.
- [x] 3.2 Changeset `feature/setupreview/0001-setup-review.sql`: `setup_review`, `setup_finding` and `setup_finding_acceptance`, in row-padding order with storage parameters. Include it from the master changelog. Add entities and repositories.
- [x] 3.3 `SetupReader`: one batched POST per node. A part that fails becomes null.
- [x] 3.4 `SetupRules`: the pure catalogue (D8), with a table-driven unit test per code, including the dev pair, a lock manager, three pairs, an unreachable node and an unknown HA policy.
- [x] 3.5 `SetupReviewService`:
  - run one cluster or all, under the lock;
  - persist for evaluated subjects only, and mark the rest stale;
  - enforce the minimum spacing;
  - publish SSE;
  - read the view;
  - accept and revoke, audited.
- [x] 3.6 REST under `/api/v1/clusters/{clusterId}/setup-review`, the job, and the settings `setupreview.interval` and `setupreview.min-interval`.
- [x] 3.7 `SetupRiskSignal` (`AlertSignalSource`), with a unit test for acceptance, expiry, stale findings and resolution.
- [x] 3.8 MCP tool `setup_review` (read).

## 4. Frontend

- [x] 4.1 Regenerate `openapi.json` and `schema.d.ts`.
- [x] 4.2 Notification channels:
  - a list with kind, health and bound rules;
  - a `ChannelEditor` modal per kind (labels, blur validation, test before save, enable toggle);
  - a delivery-log drawer with retry;
  - typed delete with its blast radius;
  - an `aria-live` outcome.
- [x] 4.3 Rule form: a `SETUP_RISK` template and state option.
- [x] 4.4 Feature `setupreview`:
  - a view under Configuration, with severity counts in words, category grouping and a filter in the URL;
  - findings with evidence, impact, a copyable snippet and a link to Broker configuration;
  - the unreviewed nodes stated;
  - run now;
  - accept or revoke with a reason and an expiry.

  Add it to `FEATURE_IDS` and the composition root.
- [x] 4.5 Tests by role and name: the channel editor per kind, test outcomes, typed delete, the review view's empty, unreachable and filtered-empty states, and accept.

## 5. Docs and verification

- [x] 5.1 Site guide pages: alert delivery channels and setup review. Tick the README roadmap item.
- [x] 5.2 `./mvnw` compile, and unit tests for the new code. Run `npm run lint`, `typecheck` and `test`.
- [x] 5.4 End to end, against a real Artemis 2.44 broker and Postgres:
  - a review finds the loopback connector and the plaintext acceptors;
  - a `SETUP_RISK` rule fires to a signed webhook whose signature verifies;
  - accepting the risk resolves the firing and sends the resolution.
- [x] 5.3 Archive the change, merging its deltas into `openspec/specs/`.
