## Why

Phases 0–2 shipped a read-only product: topology, HA state, and every queue,
address, consumer, session, connection and producer across every node, refreshed
by a tiered scraper and nudged by SSE. Nothing in it can act on a broker.
`QueueDetailDrawer.tsx` says so in a comment — *"Message ops are Phase 3."*
Phase 3 gives an operator the message-level operations the MVP has been building
toward — browse, send, move, retry, DLQ replay, delete, purge — and does it under
the three CLAUDE.md non-negotiables that were theoretical until a mutation
existed: broker-friendly by construction (#1), safe by default (#2), audit
everything (#3). Message I/O in this phase is Jolokia-only by an existing recorded
decision (ADR-0002); faithful binary I/O over the Core client is Phase 4, and the
UI must say so rather than pretend otherwise (#5).

## What Changes

- **New message browse API** — `GET /api/v1/clusters/{id}/queues/{queue}/messages`
  (paged, `?node=&filter=`) and `.../messages/{messageId}`, served by one batched
  Jolokia `browse` + `MessageCount` read per call (non-negotiable #1). Every
  message carries `bodyTruncated`; the detail view carries the effective
  truncation limit and the enabling `broker.xml` snippet.
- **New message mutation API** — send
  (`POST .../messages`), and move / retry / delete / expire by ids or by filter
  (`POST .../messages/actions/{action}`), and purge
  (`DELETE .../messages`). Every mutation takes `?dryRun=true` and returns an
  affected-count estimate without acting: `countMessages(filter)` for by-filter,
  the id count for by-id, `MessageCount` for purge (non-negotiable #2).
- **New server-enforced bulk safety cap** — `studio_setting` key
  `safety.bulk-cap` (default 1000). A mutation whose dry-run count exceeds the cap
  is rejected `422` unless `?override=true`; the UI only reaches `override=true`
  behind a dry-run preview and typed confirmation of the queue name.
- **Audit for every mutation, in the command's own transaction** — an
  `audit_event` row written `PENDING` before the broker call and updated to
  `SUCCESS` / `FAILURE` with the affected count or error, all in one
  `@Transactional` method (non-negotiable #3, ADR-0011). Dry runs are audited too
  (`dry_run = true`). A new `security/ActorResolver` fills `username` /
  `source_ip` / `request_id` from the request — the literal `anonymous` until
  authentication lands in Phase 8.
- **New audit-log read API and screen** —
  `GET /api/v1/clusters/{id}/audit` (paged, filter by user / action / outcome /
  time) and a routed audit view.
- **New DLQ management view** — the dead-letter and expiry addresses are read from
  `getAddressSettingsAsJSON`, never guessed from queue names; the view lists their
  queues with per-node depth and a preview-gated "replay all".
- **Per-message truncation disclosure** — the Jolokia spike (broker notes §11)
  showed the `management-message-attribute-size-limit` cannot be read over
  Jolokia, so there is no probed capability for it. Instead every browsed message
  carries `bodyTruncated` (set when a value ends in the broker's `, + N more`
  marker) and the detail panel shows the observed limit plus the `broker.xml`
  snippet that raises it. `web/src/clusters/CapabilityLedger.tsx` (built in
  Phase 2, rendered nowhere) is mounted for the existing four capabilities; the
  message views gate on `messageIo`.
- **`NodeScrapeLimiter` → `NodeCallLimiter`** — the per-node token bucket now
  guards on-demand operator calls, not just scrape ticks (non-negotiable #1;
  today on-demand paths bypass it).
- **Live scrape cadence** — a `SchedulingConfigurer` with settings-reading
  triggers replaces the SpEL-bound `@Scheduled` cadences, removing the
  "restart to apply" caveat documented in `SettingsService`.
- **OpenAPI type generation (ADR-0019 primary path)** — `springdoc` on the
  backend, an integration test snapshots `/v3/api-docs` to `web/openapi.json`,
  `openapi-typescript` generates `web/src/api/schema.d.ts`, and the hand-written
  DTO mirrors plus `assertShape` / `assertPaged` guards in
  `web/src/api/client.ts` are deleted. Phase 2 took the recorded fallback; this
  completes the rollout.
- **New frontend DOM test harness** — Vitest + Testing Library + MSW, wired into
  `verify-web` and CI; the two Phase 2 component tests parked on it
  (`QueueGrid`, `CommandPalette`) are un-parked.
- **Five new ADRs (0020–0024)** and a second status update appended to ADR-0019.
- **No schema changeset.** `audit_event` (changeset `004-audit.sql`) already has
  every column Phase 3 needs; three (`request_id`, `source_ip`, `user_id`) are
  simply unmapped on the entity today. Released changesets 001–009 untouched.
- **New dependencies** — backend:
  `springdoc-openapi-starter-webmvc-ui:3.1.0` (built against Spring Boot 4.1.0,
  confirmed). Frontend dev-only: `vitest`, `@vitest/*`, `jsdom`,
  `@testing-library/{react,jest-dom,user-event}`, `msw`, `openapi-typescript`.

## Capabilities

### New Capabilities

- `message-operations`: browsing a queue's messages with full headers and
  properties over Jolokia, the per-message truncation flag, sending a message,
  and moving / retrying / deleting / expiring / purging by ids or by filter —
  each mutation with `?dryRun=true` returning an affected-count estimate, a
  server-enforced bulk cap with a typed-confirmation override, and an
  `audit_event` written in the command's own transaction.
- `audit-log`: the actor-resolution rule (principal or `anonymous`, source IP,
  request id), the `audit_event` lifecycle (`PENDING` → `SUCCESS` / `FAILURE`
  in one transaction, dry runs included), and the filtered paged read API for
  the audit-log screen.
- `dlq-management`: dead-letter and expiry addresses discovered from broker
  address settings (never inferred from names), the per-node depth view of their
  queues, and preview-and-cap-gated bulk replay.

### Modified Capabilities

- `studio-settings`: a new operational setting `safety.bulk-cap` (default 1000),
  read and overridable at runtime like the scrape cadences.
- `scrape-scheduling`: scrape cadence changes take effect without a restart via a
  `SchedulingConfigurer`; the per-node management-call rate limiter
  (`NodeCallLimiter`) now also guards on-demand operator-initiated broker calls,
  not only scrape ticks.

## Impact

- **Code**: new backend `broker/MessageBrowser`, `broker/MessageOperations`,
  `service/MessageService`, `service/AuditQueryService`, `service/DlqService`,
  `security/ActorResolver`, `web/MessageController`, `web/AuditController`,
  `web/DlqController`, `web/dto/{MessageViews,MessageRequests,AuditViews}`;
  modified `broker/BrokerXmlSnippets` (truncation snippet),
  `persist/{AuditEventEntity,AuditService,AuditEventRepository}`,
  `service/SettingsService`, `config/ArtemisStudioProperties`,
  `web/ApiExceptionHandler`, `scheduler/{NodeScrapeLimiter→NodeCallLimiter,ScrapeScheduler}`.
  Frontend: new `src/messages/**`, `src/audit/AuditView.tsx`, `src/dlq/DlqView.tsx`,
  `src/shared/ConfirmByTyping.tsx`, generated `src/api/schema.d.ts`, rewritten
  `src/api/client.ts`; modified `src/router.tsx`, `src/grid/VirtualTable.tsx`
  (row selection), `src/app/ClusterLayout.tsx`, `src/queues/QueueDetailDrawer.tsx`,
  `src/settings/SettingsView.tsx` (mount `CapabilityLedger`, bulk-cap field).
- **APIs**: adds `GET|POST|DELETE /api/v1/clusters/{id}/queues/{queue}/messages`,
  `GET .../messages/{messageId}`, `POST .../messages/actions/{action}`,
  `GET /api/v1/clusters/{id}/audit`, `GET /api/v1/clusters/{id}/dlq`, and
  `/v3/api-docs` (+ Swagger UI).
- **Dependencies**: backend `+springdoc-openapi-starter-webmvc-ui:3.1.0`;
  frontend dev `+vitest`, `+jsdom`, `+@testing-library/{react,jest-dom,user-event}`,
  `+msw`, `+openapi-typescript`.
- **Config**: `application.yml` gains `artemis-studio.safety.bulk-cap: 1000` and
  (per slice-0 findings) an optional `artemis-studio.browse.max-rows`; both
  overridable from `studio_setting`.
- **Schema**: none. `AuditEventEntity` gains three `@Column` mappings for columns
  that already exist in changeset `004-audit.sql`.
- **Docs**: ADRs 0020–0024 added, ADR-0019 status-updated; `docs/architecture.md`
  gains the bulk-cap and DLQ paragraphs it lacks; `docs/broker-management-notes.md`
  gains a Phase 3 surface-checks section (§11); `README.md` Phase 3 rows ticked;
  `openspec/project.md` current-phase line updated.
