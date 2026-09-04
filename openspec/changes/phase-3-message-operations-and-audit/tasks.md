## 1. ADRs and conventions

- [x] 1.1 Write `docs/adr/0021-message-operations-jolokia-only.md` — Phase 3 message I/O is the one concrete Jolokia path, no transport interface (ADR-0002 extracts it in Phase 4 from two real impls); truncation disclosed **per message** via the broker's `, + N more` marker + a `broker.xml` snippet in the detail panel — **no `MESSAGE_BODY_FULL` capability** (slice 0 proved the limit is unreadable over Jolokia); `MESSAGE_IO` stays Jolokia-degraded, faithful binary deferred. *(Revised after slice 0.)*
- [x] 1.2 Write `docs/adr/0022-dry-run-estimate-and-server-enforced-bulk-cap.md` — dry-run affected count is a broker-side estimate (`countMessages(filter)` / id count / `MessageCount`), labelled point-in-time, and is itself audited (`dry_run = true`); `safety.bulk-cap` in `studio_setting` (default 1000), enforced server-side, `422 bulk-cap-exceeded` with `affectedCount`/`cap` unless `?override=true`; UI reaches override only behind preview + typed confirmation
- [x] 1.3 Write `docs/adr/0023-audit-actor-before-authentication.md` — `security/ActorResolver` returns `Actor(username, sourceIp, requestId)`: principal from `SecurityContextHolder` else literal `anonymous`; `sourceIp` from `getRemoteAddr()`; `requestId` from `X-Request-Id` else generated; scheduler rows stay `system`; `AuditEventEntity` maps the three already-migrated columns, no changeset
- [x] 1.4 Write `docs/adr/0024-frontend-dom-test-harness.md` — Vitest + `@testing-library/react` + `user-event` + `jest-dom` + `jsdom` + `msw`; reuses the installed Vite/esbuild; `npm test` joins `verify-web` and CI; recorded fallback to `happy-dom` or `node:test` if `jsdom` won't install under the env allow-scripts policy
- [x] 1.5 Write `docs/adr/0025-live-scrape-cadence-scheduling-configurer.md` — replace the SpEL-bound `@Scheduled` cadences with a `SchedulingConfigurer` registering three `Trigger`s that re-read `SettingsService` on every `nextExecution`; removes the "restart to apply" caveat
- [x] 1.6 Append a second "## Status update (Phase 3 implementation)" to `docs/adr/0019-openapi-generated-frontend-types.md` — primary path now taken; the snapshot is produced by an integration test (`GET /v3/api-docs` → committed `web/openapi.json`), not a build plugin needing a running server; CI's existing `git diff --exit-code` catches drift. Decision unchanged
- [x] 1.7 `openspec/project.md` — leave the current-phase line to group 14; touch here only if a convention bullet needs adding

## 2. Broker-surface verification spike (Slice 0)

- [x] 2.1 `just up` (brokers only — `artemis-primary` + `artemis-backup`); created anycast queues `PHASE3.SRC` / `PHASE3.DST`, sent text messages incl. a 4000-char body (truncated to 256 by the broker default) via `sendMessage(...)`
- [x] 2.2 Captured verbatim into `src/test/resources/jolokia/`: `browse.json` (`browse(int,int,String)`), `browse-truncated.json` (oversized message), `browse-bad-filter.json` (invalid-filter error), `count-messages.json` (`countMessages(filter)` — numeric predicate `AMQSize > 1000` **works**, unlike the §10 `list*` options)
- [x] 2.3 Captured `move-messages.json`, `remove-messages.json`, `remove-all-messages.json` (purge), `retry-messages.json`, `send-message.json` (returns the new id as a String), `address-settings.json` (`getAddressSettingsAsJSON("#")` — has `deadLetterAddress` / `expiryAddress`; has **no** attribute-size-limit key)
- [x] 2.4 Appended "## 11. Phase 3 surface checks" to `docs/broker-management-notes.md` — signature table + verdict table + §11.3 (`MESSAGE_BODY_FULL` correction) + §11.4 (net plan changes)
- [x] 2.5 **Not needed.** §11 verdict: `browse(int page, int pageSize, String filter)` pages server-side (1-based) and the broker caps at `managementBrowsePageSize` (200). No `browse.max-rows` setting; `MessageService.browse` passes `(page, size, filter)` straight through.

## 3. OpenAPI-generated frontend types (Slice 1 — ADR-0019 primary path)

- [x] 3.1 `pom.xml` — added `org.springdoc:springdoc-openapi-starter-webmvc-ui:${springdoc.version}` (3.1.0); resolves + compiles on Boot 4.1; `/v3/api-docs` + Swagger UI serve. `config/OpenApiConfig` pins `info` and drops the server list for a host-independent snapshot
- [x] 3.2 `src/test/java/.../web/OpenApiSnapshotTest.java` (extends `PostgresIntegrationTest`, MockMvc) — `GET /v3/api-docs`, key-sorted, writes `web/openapi.json`; fails on drift. Green, deterministic (17 paths / 30 schemas)
- [x] 3.3 `web/package.json` — dev-dep `openapi-typescript@7.13.0`; `web/src/api/schema.d.ts` generated from the committed `web/openapi.json`
- [x] 3.7 **DTO requiredness pass** (was the 3.4 blocker): `@Schema(requiredMode = REQUIRED)` / `@Schema(nullable = true)` on every component of `ClusterViews` / `ResourceViews` / `SettingsViews`; `@ApiResponse` on `ClusterController.register` so springdoc emits `RegisterPreview` + `ClusterDetail` for its `ResponseEntity<Object>`. Convention for later DTOs: annotate every response field; `nullable = true` for the `| null` ones
- [x] 3.4 Rewrote `web/src/api/client.ts` onto `components["schemas"]` aliases; deleted every hand-written DTO interface + `assertShape` / `assertPaged`; kept `ApiError`, `keys`, generic `PagedView<T>`. `web/src/topology/layout.ts` coerces the now-`?:` nullable fields with `?? null`
- [x] 3.5 `.github/workflows/ci.yml` — frontend job: `npm run build` (runs `gen:api`) then `git diff --exit-code src/api/schema.d.ts`; backend job's existing `git diff --exit-code` now also guards `web/openapi.json` (rewritten by `OpenApiSnapshotTest` in `mvn verify`)
- [x] 3.6 `verify-web` green (`gen:api` → `tsc -b` → `vite build` + lint); backend web/service tests green; `gen:api` no-diff on a clean tree

## 4. Frontend DOM test harness (Slice 2 — ADR-0024)

- [x] 4.1 `web/package.json` — dev-deps pinned exact: `vitest@4.1.11`, `jsdom@30.0.1`, `@testing-library/react@16.3.3`, `@testing-library/user-event@14.6.7`, `@testing-library/jest-dom@6.9.1`, `msw@2.15.0` (coverage-v8 skipped — not needed yet). Versions checked via `ctx7`
- [x] 4.2 `web/vitest.config.ts` (standalone, `@vitejs/plugin-react`, `environment: 'jsdom'`, `globals`, `setupFiles`) + `web/src/test/setup.ts` (`@testing-library/jest-dom/vitest` matchers, MSW `server` with `beforeAll`/`afterEach`/`afterAll` + `onUnhandledRequest: 'error'`, `cleanup()`, and jsdom shims: `matchMedia`, one-shot `ResizeObserver`, non-zero `offset{Width,Height}` for `react-virtual`) + `web/src/test/render.tsx` (Mantine + Query providers); `"test": "vitest run"`
- [x] 4.3 Migrated `web/src/topology/layout.test.ts` to Vitest (`describe`/`it`/`expect`); `test` script no longer uses `--experimental-strip-types`
- [x] 4.4 Un-parked: `web/src/grid/VirtualTable.test.tsx` — rows render, `aria-sort` cycles none→ascending→descending on header click (asserts the emitted next-sort values too), empty label, `onRowClick`; `web/src/palette/CommandPalette.test.tsx` — opens on `{Control>}k`, filters, an invoked cluster/queue action calls `navigate` with the right args (`@tanstack/react-router` mocked, API via MSW). Queried by role + accessible name
- [x] 4.5 `justfile` `verify-web` = `build` + `lint` + `test`; `.github/workflows/ci.yml` frontend job runs `npm test` after lint; `tsconfig.node.json` includes `vitest.config.ts`; eslint override for `src/test/**` + `*.test.*` (node globals, allow non-component exports)
- [x] 4.6 `verify-web` green: `npm run build` (gen:api no drift, `tsc -b`, `vite build`), `npm run lint` clean, `npm test` = 10 passing (3 files). `jsdom` installed fine — no fallback; recorded in ADR-0024's status update

## 5. Message browse — backend (Slice 3a)

- [x] 5.1 `scheduler/NodeScrapeLimiter.java` → rename to `scheduler/NodeCallLimiter.java` (class + file + tests + injection points); no behaviour change
- [x] 5.2 `service/PagedListService.java` + `service/MessageService.java` acquire a `NodeCallLimiter` permit before every on-demand broker POST (closes the bypass; non-negotiable #1). **Deviation:** not threaded into `broker/CapabilityProbe` — it is constructed with no deps in 4 unit tests and pre-persist during registration has no node to key a bucket on; the probe is a rare one-shot operator action and its per-node entry point (`ClusterService.capabilities`) can gate at the call site if ever needed. Live fan-out + message paths (the hot ones) are covered.
- [x] 5.3 `broker/BrokerXmlSnippets.java` — add `forMessageBodyLimit()` returning the `<address-setting match="#"><management-message-attribute-size-limit>-1</…></address-setting>` snippet (used by the browse detail panel, not by a capability)
- [x] 5.4 `broker/CapabilityProbe.java` — tighten the `messageIo` reason string to say "bodies carried as text; faithful binary I/O is Phase 4". **No new capability** — slice 0 proved the attribute-size limit is not readable over Jolokia (broker notes §11.3); `BrokerCapabilities` / `ClusterViewMapper` unchanged
- [x] 5.5 `broker/MessageBrowser.java` — `browse(client, queueMbean, page, size, filter)` → one `batch([ exec(mbean, "browse(int,int,java.lang.String)", page, size, filter), read(mbean, "MessageCount") ])`; `value` is a **plain JSON array** (no double-decode); `decodeRow` maps `messageID/type/priority/durable/expiration/timestamp/userID/persistentSize` + the eight typed `*Properties` maps + `text`; `bodyTruncated` = any string value matches `/, \+ \d+ more$/`; `observedLimit` = `visibleLen − markerLen` on a truncated row; invalid-filter `AMQ229020` → `IllegalArgumentException` (→ 400)
- [x] 5.6 `web/dto/MessageViews.java` — `MessageSummaryView(messageId, type, durable, priority, timestamp, expiration, size, groupId, correlationId, bodyPreview, bodyTruncated, propertyCount)`, `MessageDetailView(+ headers map, stringProperties/intProperties/longProperties/doubleProperties/booleanProperties maps, body, bodyTruncated, observedLimitBytes, node)`; `@Schema` annotations
- [x] 5.7 `service/MessageService.java` — `browse(clusterId, queueName, nodeId?, filter, page, size)`: resolve `address`/`routingType` from `QueueSnapshotRepository`, resolve the message-holding live node when `nodeId` omitted (echo it back), `limiter.acquire`, build the MBean via `BrokerMBeans.queue`, call `MessageBrowser` with `(page, size, filter)` — the **broker pages**, no Studio-side slicing — return `PagedView<MessageSummaryView>` with total = `MessageCount`; `detail(clusterId, queueName, messageId, nodeId?, filter?)` = `browse(1, brokerPageCap, filter)` scanned in memory for the `messageId`
- [x] 5.8 `web/MessageController.java` — `GET /api/v1/clusters/{clusterId}/queues/{queueName}/messages?node=&filter=&page=&size=` → `PagedView<MessageSummaryView>`; `GET …/messages/{messageId}?node=&filter=` → `MessageDetailView` (404 if not in range); `@Transactional(readOnly = true)`; gate on `MESSAGE_IO` (typed problem + `broker.xml` snippet when not `AVAILABLE`, same pattern as `ClusterResourceController`)
- [x] 5.9 `broker/MessageBrowserTest` — decode against `browse.json` (4 rows, typed prop maps, plain array) / `browse-truncated.json` (marker → `bodyTruncated`, `observedLimit ≈ 256`) / `browse-bad-filter.json` (→ `IllegalArgumentException`)
- [x] 5.10 `web/MessageBrowseControllerTest` (MockMvc, `@MockitoBean` broker connections backed by the slice-0 fixtures) — page shape, single-message detail + 404, one POST per browse (assert on `MockRestServiceServer`), capability-gated path, node echoed, bad filter → 400

## 6. Message browse — frontend (Slice 3b)

- [x] 6.1 `web/src/api/client.ts` — `useMessages(clusterId, queueName, {node,filter,page})` and `useMessageDetail(...)` hooks + `keys.messages(...)`; regenerate `schema.d.ts`
- [x] 6.2 `web/src/grid/VirtualTable.tsx` — add optional row selection: a leading checkbox column driven by props `selected: Set<string>` / `onSelectChange` / `selectable`, header select-all for the loaded page; keyboard operable, `aria` on the checkboxes; no change to callers that don't pass `selectable`
- [x] 6.3 `web/src/messages/MessagesView.tsx` — route component; `VirtualTable` with the message column set; a node `Select` shown only when the logical node has >1 endpoint; a filter `TextInput` debounced 250ms exactly as `ResourceView`; teaching empty state; skeleton past 1s
- [x] 6.4 `web/src/messages/MessageDetailPanel.tsx` — right-hand panel (Drawer or split) with headers table, properties tables, body in `@mantine/code-highlight`; when `bodyTruncated`, a `<Alert>` naming `observedLimitBytes` and rendering the `forMessageBodyLimit()` `<address-setting>` snippet via `CodeHighlight`, plus one line that faithful binary bodies arrive with the Core client in Phase 4
- [x] 6.5 `web/src/router.tsx` — add `/clusters/$clusterId/queues/$queueName/messages` with a `validateMessagesSearch({node?,filter?,page?,selected?})` in the `validateResourceSearch` shape
- [x] 6.6 `web/src/clusters/CapabilityLedger.tsx` — mount it: in `SettingsView` and in `ClusterLayout`'s header when any capability is not `AVAILABLE` (the existing four; no new row)
- [x] 6.7 `web/src/queues/QueueDetailDrawer.tsx` — add a "Browse messages" link to the messages route; drop the "Message ops are Phase 3" comment
- [x] 6.8 `web/src/app/ClusterLayout.tsx` — no new top-level tab for messages (it is reached from a queue row); confirm the breadcrumb/back path works
- [x] 6.9 Component tests (new harness) — `MessageDetailPanel` renders the truncation banner when `bodyTruncated` and not otherwise; `MessagesView` shows the node selector only with >1 endpoint; queried by role/name
- [x] 6.10 `verify-web` green

## 7. Send message (Slice 4)

- [x] 7.1 `web/dto/MessageRequests.java` — `SendMessageRequest(type, durable, body, headers: Map, properties: Map)` with `@NotNull`/`@Valid`
- [x] 7.2 `broker/MessageOperations.java` — `send(client, addressMbean, req)` → `exec(addressMbean, "sendMessage(java.util.Map,int,java.lang.String,boolean,java.lang.String,java.lang.String)", …)`; return the new id if slice 0 showed one
- [x] 7.3 `service/MessageService.java` — `send(clusterId, queueName, nodeId?, req, dryRun, actor)`: `@Transactional`; `audit.begin("SEND_MESSAGE", "QUEUE", queueName, …, dryRun)`; `dryRun` → `audit.succeed(event, 1)` and return `DryRunResult(1, cap, false)` with no broker call; else `limiter.acquire` → `MessageOperations.send` → `audit.succeed(event, 1)` → `afterCommit` `hub.publish(clusterId, "queues")` → `Attempt.Ok`; broker failure → `audit.fail` → `Attempt.Failed`
- [x] 7.4 `web/MessageController.java` — `POST …/queues/{queueName}/messages?dryRun=&node=` `@Valid SendMessageRequest` → `202`/`200` `AffectedView` or `DryRunView`; unwrap `Attempt` as `ClusterController.unwrap`
- [x] 7.5 `web/src/messages/SendMessage.tsx` — modal from `MessagesView`: body `Textarea`, type `Select`, durable `Switch`, a key/value property editor; a note that the body is text over Jolokia, binary is Phase 4; on success `notifications.show` + invalidate `keys.messages`
- [x] 7.6 `web/MessageSendControllerTest` — send writes an audit row `SUCCESS` count 1; `dryRun=true` writes `dry_run = true` and issues no mutating Jolokia call (assert on `MockRestServiceServer`); validation failure → `400` problem, no audit row
- [x] 7.7 `verify-web` green

## 8. Operations by message id — move / retry / delete / expire (Slice 5)

- [x] 8.1 `web/dto/MessageRequests.java` — `MessageActionRequest(messageIds: List<Long>?, filter: String?, targetQueue: String?)`; a `MessageAction` enum `MOVE|RETRY|DELETE|EXPIRE` with `auditName()` and the Jolokia op signature per form
- [x] 8.2 `broker/MessageOperations.java` — `byId(client, queueMbean, action, id, target?)` and (group 9) `byFilter(...)`; each one `exec`; sum results when the broker returns per-call counts, else count ids
- [x] 8.3 `service/MessageService.java` — `execute(clusterId, queueName, nodeId?, action, req, dryRun, override, actor)`: `@Transactional`; `audit.begin(action.auditName(), "QUEUE", queueName, …, {ids|filter, target}, dryRun)`; by-id `dryRun` → `succeed(event, ids.size)` return `DryRunResult`; else `limiter.acquire` → loop/`exec` → `succeed(event, n)` → `afterCommit` publish → `Attempt.Ok`; failure → `fail` → `Attempt.Failed`
- [x] 8.4 `web/MessageController.java` — `POST …/queues/{queueName}/messages/actions/{action}?dryRun=&node=&override=` → `AffectedView` / `DryRunView`
- [x] 8.5 `web/src/messages/MessageActions.tsx` — a sticky action bar shown when the selection `Set` is non-empty: selected count, Move / Retry / Delete / Expire buttons; a per-action confirm dialog (Move takes a target-queue field); on success clear selection, `notifications.show`, invalidate
- [x] 8.6 `web/src/messages/MessagesView.tsx` — own the `selected` `Set<string>` (ephemeral React state, keyed by `messageId`); pass `selectable` to `VirtualTable`; clear on filter/node/page change
- [x] 8.7 `web/MessageIdActionsControllerTest` — each action: audit row `SUCCESS` with the right count; broker failure → audit `FAILURE` + RFC 9457 response; `dryRun=true` → `dry_run = true`, no mutating call
- [x] 8.8 `verify-web` green

## 9. Operations by filter, dry-run preview, and the safety cap (Slice 6)

- [x] 9.1 `config/ArtemisStudioProperties.java` + `application.yml` — `artemis-studio.safety.bulk-cap: 1000` (+ `browse.max-rows` from 2.5 if applicable)
- [x] 9.2 `service/SettingsService.java` — `bulkCap()` over key `safety.bulk-cap`, stored override else the property default; validation rejects non-positive; add to the `GET /api/v1/settings` effective-values response and its `FIELDS` metadata
- [x] 9.3 `service/BulkCapExceededException.java` + `web/ApiExceptionHandler.java` — map to `422`, `type: https://artemis-studio.dev/problems/bulk-cap-exceeded`, properties `affectedCount`, `cap`
- [x] 9.4 `broker/MessageOperations.java` — `countMessages(client, queueMbean, filter)` → `exec(mbean, "countMessages(java.lang.String)", filter)`
- [x] 9.5 `service/MessageService.java` — by-filter branch of `execute(...)`: `dryRun` or pre-flight → `count = countMessages(filter)`; `if (count > settings.bulkCap() && !override) throw new BulkCapExceededException(count, cap)`; `dryRun` returns `DryRunResult(count, cap, count > cap)` (audited); else proceed and audit the broker's actual affected count
- [x] 9.6 `web/src/shared/ConfirmByTyping.tsx` — extract the typed-name confirmation from `clusters/AddManagementUrl.tsx#RemoveCluster` and `settings/SettingsView.tsx#CredentialRotation`; props `{token, label, confirmLabel, loading, onConfirm}`; refactor those two call sites onto it
- [x] 9.7 `web/src/messages/BulkActionPreview.tsx` — modal for a by-filter action: filter field, a "Preview" button that calls the endpoint with `dryRun=true`, the returned count shown as "≈ N (estimate)", the cap; when `count > cap` a `ConfirmByTyping` on the queue name gates a "Run anyway" button that resends with `override=true`
- [x] 9.8 `web/src/messages/MessageActions.tsx` — a "By filter…" entry point opening `BulkActionPreview`
- [x] 9.9 `MessageService` unit test — cap at boundary (N == cap allowed, N == cap+1 refused), refused without `override`, allowed with `override`; dry-run over cap returns `over = true` and is audited
- [x] 9.10 `web/MessageFilterActionsControllerTest` — `422 bulk-cap-exceeded` body carries `affectedCount`/`cap`; `override=true` proceeds; dry-run issues only `countMessages`, no mutating call
- [x] 9.11 Component test — `BulkActionPreview` keeps "Run anyway" disabled until the queue name is typed
- [x] 9.12 `verify-web` green

## 10. Purge (Slice 7)

- [x] 10.1 `broker/MessageOperations.java` — `purge(client, queueMbean)` → `exec(mbean, "removeAllMessages()")`
- [x] 10.2 `service/MessageService.java` — `purge(clusterId, queueName, nodeId?, dryRun, override, actor)`: `@Transactional`; dry-run count = queue `MessageCount`; same cap check; `audit.begin("PURGE_QUEUE", …)`; `afterCommit` publish; `Attempt`
- [x] 10.3 `web/MessageController.java` — `DELETE …/queues/{queueName}/messages?dryRun=&node=&override=` → `AffectedView` / `DryRunView`
- [x] 10.4 `web/src/queues/QueueDetailDrawer.tsx` (or `MessagesView` header) — a "Purge queue" danger action gated by `ConfirmByTyping` on the queue name, showing the dry-run count first
- [x] 10.5 `web/MessagePurgeControllerTest` — purge audits `PURGE_QUEUE` `SUCCESS` with the removed count; dry-run no-ops with `dry_run = true`; over-cap without override → `422`
- [x] 10.6 `verify-web` green

## 11. Audit actor and the audit-log screen (Slice 8)

- [ ] 11.1 `persist/AuditEventEntity.java` — add `@Column` mappings for `request_id`, `source_ip` (`inet` → `String`/`InetAddress`), `user_id`; keep `@Getter`-only + named mutators
- [ ] 11.2 `security/ActorResolver.java` — `@Component`, `Actor resolve()` reading `SecurityContextHolder` (else `anonymous`), the current `HttpServletRequest` `getRemoteAddr()`, and the `X-Request-Id` header (else `UUID.randomUUID()`); `Actor systemActor()` for the scheduler
- [ ] 11.3 `persist/AuditService.java` — `begin(...)` gains an `Actor` parameter and stores `username`/`source_ip`/`request_id`/`user_id`; existing callers (`ClusterService`) pass `actorResolver.resolve()`; the `SYSTEM_USER` constant becomes `Actor.system()`
- [ ] 11.4 `persist/AuditEventRepository.java` — a filtered paged query `findPage(clusterId, username?, action?, outcome?, from?, to?, Pageable)` (`@Query` or a small `Specification`), newest first
- [ ] 11.5 `web/dto/AuditViews.java` — `AuditEventView(ts, username, sourceIp, requestId, action, targetType, targetName, affectedCount, outcome, dryRun, params, error, nodeId)`; `PagedView` reused; `@Schema`
- [ ] 11.6 `service/AuditQueryService.java` + `web/AuditController.java` — `GET /api/v1/clusters/{clusterId}/audit?user=&action=&outcome=&from=&to=&page=&size=` → `PagedView<AuditEventView>`; `@Transactional(readOnly = true)`
- [ ] 11.7 `web/src/audit/AuditView.tsx` + route `/clusters/$clusterId/audit` — filters (`user`/`action`/`outcome`/`from`/`to`) in the URL search params; `VirtualTable` with an expandable row for `params`/`error`; an outcome cell = status word + `--as-ok`/`--as-warning`/`--as-danger` mark (word not colour-only)
- [ ] 11.8 `web/src/app/ClusterLayout.tsx` — add the "Audit" tab; `web/src/settings/SettingsView.tsx` — update the footer line that currently says "the audit-log viewer is Phase 3"
- [ ] 11.9 `AuditServiceTest` / `ActorResolverTest` — anonymous when no principal; `X-Request-Id` preserved else generated; scheduler rows are `system`
- [ ] 11.10 `web/AuditControllerTest` — filter by action+outcome returns only matching newest-first; time range narrows; a mutation from group 8 shows up
- [ ] 11.11 Component test — `AuditView` renders the outcome word for a failed row; expanding shows `params`/`error`
- [ ] 11.12 `verify-web` green

## 12. DLQ management view (Slice 9)

- [ ] 12.1 `service/DlqService.java` — read `getAddressSettingsAsJSON` (per slice-0 keys) for `deadLetterAddress` / `expiryAddress`; list `QueueSnapshotRepository` rows on those addresses with per-node depth; when the settings read fails return an explicit `unavailable` marker, never a name match
- [ ] 12.2 `web/dto/MessageViews.java` — `DlqView(addresses: [{address, kind, queues: [{queueName, perNodeDepth}]}], settingsAvailable: boolean)`
- [ ] 12.3 `web/DlqController.java` — `GET /api/v1/clusters/{clusterId}/dlq` → `DlqView`; `@Transactional(readOnly = true)`
- [ ] 12.4 `web/src/dlq/DlqView.tsx` + route `/clusters/$clusterId/dlq` + `ClusterLayout` "DLQ" tab — one row per DLQ/expiry queue with per-node depth; a "Replay all" action wired to the group-9 by-filter `RETRY` via `BulkActionPreview` (preview + cap + audit); a link into `MessagesView` for the queue; an explicit "dead-letter configuration unavailable" state when `settingsAvailable` is false
- [ ] 12.5 `DlqServiceTest` — addresses come from settings; unreadable settings → `settingsAvailable=false` and nothing inferred
- [ ] 12.6 `web/DlqControllerTest` (fixture-backed) — the discovered addresses and their queues; the unavailable path
- [ ] 12.7 `verify-web` green

## 13. Live scrape cadence (Slice 10 — ADR-0025)

- [ ] 13.1 `scheduler/ScrapeScheduler.java` — replace the three SpEL `@Scheduled(fixedDelayString = "#{@settingsService…}")` methods with `implements SchedulingConfigurer`; in `configureTasks(registrar)` register three `registrar.addTriggerTask(runnable, trigger)` where each `Trigger.nextExecution` reads the current `SettingsService` interval; keep the pooled `TaskScheduler`
- [ ] 13.2 `service/SettingsService.java` — drop the "changes take effect only on restart" javadoc caveat; confirm `tierA/B/C` getters are cheap enough to call per trigger
- [ ] 13.3 `web/src/settings/SettingsView.tsx` — remove the "restart to apply" caption from the scrape-cadence fields
- [ ] 13.4 `ScrapeSchedulerTest` — a shortened interval schedules the next run sooner without a context restart; tiers still fire independently; one POST per node per tier preserved
- [ ] 13.5 `verify-api` green

## 14. Docs, verification, close-out

- [ ] 14.1 `README.md` — tick the nine Phase 3 rows; refresh the status line ("Message operations are Phase 3" → done)
- [ ] 14.2 `docs/architecture.md` — add the bulk-cap and DLQ paragraphs the "Safety and audit" section currently lacks; note message operations are Jolokia-only, gated on `MESSAGE_IO`, with per-message truncation disclosure (no new capability); capability list stays at four
- [ ] 14.3 `openspec/project.md` — current-phase line → Phase 3 complete, Phase 4 next
- [ ] 14.4 `just fmt` (Spotless + `eslint --fix`) then `just verify` (`verify-api` + `verify-web`) — both green, output pasted, no claimed receipts
- [ ] 14.5 Manual acceptance against `just up` with the dev pair (needs host disk headroom — Phase 2 §10 records a broker that blocked all production at 98.5%): browse with headers/properties/body; truncation banner appears over the limit and clears after raising `management-message-attribute-size-limit`; send → appears in browse; move one by id, both depths update within a tier-B tick; dry-run a by-filter delete (count, no change) then run it (depth drops by exactly that count); set `safety.bulk-cap` to 5, a 20-message filter is refused naming count+cap, typed-confirm override succeeds; purge behind typed confirmation; audit screen shows all of the above filterable by action/outcome, a failure row (node pointed at a stopped broker) shows `FAILURE` + error; DLQ view lists the real DLA-backed queues, "replay all" previews then retries; change a scrape cadence in Settings and see it apply with no restart; a `MESSAGE_IO`-unavailable cluster shows the ledger reason + `broker.xml` snippet, no missing buttons
- [ ] 14.6 `/opsx:archive` — move the change to `openspec/changes/archive/`, merge `specs/` deltas into `openspec/specs/`, open the PR
