## Context

See `proposal.md` — Why. This is the design-level detail behind it.

**Current state after Phase 2:**

- `broker/JolokiaBrokerClient.batch(List<JolokiaRequest>)` exists, is unit-tested,
  and has **zero production call sites** — every live path uses one `single` POST.
  Phase 3 is its first real use.
- `persist/AuditService` (`begin` → `succeed` / `fail`) and the `audit_event`
  table (changeset `004-audit.sql`, 16 columns) exist and are exercised by
  `ClusterService.register` / `rotateCredentials`. `audit_event.username` is a
  hard-coded `"system"`; `request_id`, `source_ip`, `user_id` are columns in SQL
  with no field on `AuditEventEntity`.
- `broker/BrokerCapabilities` is a record of four `CapabilityAssessment`s (not a
  map); `broker/CapabilityProbe.probe(client)` derives them live per request;
  nothing is stored. `messageIo` today = `AVAILABLE (degraded)` iff
  `managementWrite` is available.
- `web/src/clusters/CapabilityLedger.tsx` — the honest-gating UI required by
  non-negotiable #5 — is fully built and **rendered nowhere**.
- `scheduler/NodeScrapeLimiter.acquire(nodeId)` guards only `ScrapeScheduler`;
  `PagedListService`, `ClusterService`, `CapabilityProbe` bypass it.
- `ScrapeScheduler` binds tier cadences with SpEL
  (`fixedDelayString = "#{@settingsService.tierAMillis()}"`) — resolved once at
  wiring time, so a cadence change needs a restart. No `SchedulingConfigurer`.
- Frontend types are hand-written in `web/src/api/client.ts` with `assertShape` /
  `assertPaged` runtime guards — ADR-0019's recorded fallback. No `springdoc`.
- Frontend has no DOM test harness: one `node:test` file, no vitest / RTL / MSW.
- `web/src/grid/VirtualTable.tsx` is TanStack Table v9 headless
  (`tableFeatures({})` — all client features off) with server-side URL-owned
  sort/filter/page and **no row selection**.

**Binding constraints:**

- CLAUDE.md non-negotiables #1 (broker-friendly: batched, tiered, rate-limited),
  #2 (safe by default: `?dryRun=true`, typed confirmation, real cap), #3 (audit
  in the command's transaction), #5 (honest capability gating with `broker.xml`
  snippet), #6 (three-layer tokens), #8 (logical CSS properties), #9 (state has
  one owner).
- ADR-0002 — Jolokia is the only channel until Phase 4; the Core-client interface
  is extracted then from two real implementations, "not guessed up front" ⇒ **no
  transport abstraction in Phase 3**.
- ADR-0011 — audit is written in the command's own `@Transactional` method,
  `PENDING` before the broker call, `SUCCESS` / `FAILURE` after.
- ADR-0015 — network I/O never inside a DB transaction *for the scheduler*;
  user-initiated commands (`ClusterService`) deliberately hold the broker call
  inside the transaction so the audit row and the state change commit atomically.
  Phase 3 mutations follow the `ClusterService` model, not the scheduler model.
- ADR-0008 — never edit a released changeset. `docs/broker-management-notes.md` is
  the verified broker surface; §8 draws the Phase 3/4 line.

## Goals / Non-Goals

**Goals:**

- Every message mutation reachable only through a path that (a) can dry-run
  without acting, (b) enforces the bulk cap server-side, (c) writes its audit row
  in the same transaction.
- One Jolokia POST per browse and per operation, all through `batch()`, all
  through the (renamed) per-node limiter.
- Truncation made visible per message and gated as a first-class capability.
- The three Phase 2 fast-follows closed: OpenAPI generation on its primary path,
  a DOM test harness, live scrape cadence.
- Each slice leaves a working, shippable product.

**Non-Goals (design-level boundaries):**

- No Core client, no `activemq.notifications`, no push events, no faithful binary
  message I/O — all Phase 4.
- No transport interface / `MessageChannel` abstraction — one concrete Jolokia
  path only (ADR-0002).
- No new SSE topic. No `messages`/`audit` stream events — nothing polls them.
- No RBAC, no permission checks on mutations — Phase 8. The actor is resolved and
  recorded, not authorised.
- No schema changeset. No message persistence — browse is pass-through.
- No re-sortable / broker-sorted message grid — Artemis 2.44 `sortColumn` 500s
  (Phase 2 notes §10). Browse paging is done at the broker via
  `browse(page, size, filter)` (slice 0, notes §11), 1-based, broker-capped at
  `managementBrowsePageSize` (200); Studio does not slice.

## Decisions

### D1 — One concrete Jolokia path, `batch()` for every operation (ADR-0002)

`broker/MessageBrowser` and `broker/MessageOperations` are plain components that
take a `JolokiaBrokerClient` and speak `JolokiaRequest` directly. Browse is
`batch([ exec(queueMbean, "browse(java.lang.String)", filter),
read(queueMbean, "MessageCount") ])`. A by-filter mutation's dry run is
`batch([ exec(queueMbean, "countMessages(java.lang.String)", filter) ])`; its
execution is a **separate** `batch` POST — dry-run and act are never in one POST.
The queue MBean name comes from `BrokerMBeans.queue(brokerObjectName, address,
queue, routingType)` with `address` / `routingType` read from the cached
`queue_snapshot` row, so the client only ever sends a queue name.

*Alternatives:* a `MessageChannel` interface with a Jolokia impl now — rejected,
ADR-0002 says the interface is extracted in Phase 4 from two real
implementations, not guessed; a second impl does not exist to shape it.

### D2 — `NodeScrapeLimiter` → `NodeCallLimiter`, acquired by every on-demand call (non-negotiable #1)

Rename the class and widen its role: `MessageService` and the existing on-demand
readers (`PagedListService`, `CapabilityProbe`) call `limiter.acquire(nodeId)`
before each broker POST. Same per-node `Semaphore`, same 1s refill, same
`permitsPerSecond` setting — only the set of callers grows.

*Alternatives:* a separate limiter for operator calls — rejected, two buckets
against one broker defeats the point; the ceiling is per node, not per caller
class.

### D3 — Dry-run count is a broker-side estimate, and it is audited (ADR-0022)

Artemis has no dry-run. By-filter → `countMessages(filter)`; by-id → the id
count (optionally verified against a browse); purge → the queue's `MessageCount`;
send → `1`. The response labels the number a point-in-time estimate. A dry run
still writes an `audit_event` with `dry_run = true`, `outcome = SUCCESS` — the
column exists for exactly this and "who probed what, when" is worth keeping.

*Alternatives:* browse-and-count the exact ids — rejected, expensive on deep
queues and it hits the attribute-size limit, i.e. worse for the broker (#1).

### D4 — The bulk cap is server-enforced with an explicit override (ADR-0022, non-negotiable #2)

`studio_setting` key `safety.bulk-cap`, default `1000`, via
`SettingsService.bulkCap()` and an `ArtemisStudioProperties` default. In the
command: `if (dryRunCount > cap && !override) throw new
BulkCapExceededException(count, cap)` → `ApiExceptionHandler` maps it to `422`,
`type: bulk-cap-exceeded`, properties `affectedCount` and `cap`. The frontend
sends `?override=true` only from behind the `BulkActionPreview` modal, after the
dry-run count is shown and the queue name is typed.

*Alternatives:* a hard cap with no override — rejected, a legitimate 50k-message
DLQ purge would be unrunnable; advisory-only — rejected, a scripted API call
would bypass it, so it must be server-side.

### D5 — Phase 3 mutations hold the broker call inside the transaction (ADR-0011, ADR-0015)

Each mutation is one `@Transactional` service method: `audit.begin(...)` →
`limiter.acquire` → broker POST → `audit.succeed(event, n)` /
`audit.fail(event, err)` → return `Attempt.Ok` / `Attempt.Failed`. This is the
`ClusterService.register` model — the deliberate exception ADR-0015 carves out
from "no I/O in a transaction" for user-initiated commands, so the audit row and
its outcome are atomic. `ApiExceptionHandler` already maps
`BrokerConnectionException` to RFC 9457; the controller unwraps `Attempt` exactly
as `ClusterController.unwrap` does.

*Alternatives:* audit in a separate `REQUIRES_NEW` transaction — rejected, a
crash between the two commits would leave a `PENDING` row with no resolution and
break "one unit" (#3).

### D6 — Truncation is disclosed per message, not as a capability (ADR-0021, non-negotiable #5)

The slice-0 spike (broker notes §11) proved the
`management-message-attribute-size-limit` is not exposed anywhere over Jolokia —
not in `getAddressSettingsAsJSON`, not on any broker MBean member. A probed
`MESSAGE_BODY_FULL` capability is therefore impossible, and an *observed* one
(`UNKNOWN` until a browse happens to see a big message) is weaker than the flag
it would summarise. So there is **no fifth capability** and no
`broker-capabilities` change.

Instead: the broker appends a literal `, + <N> more` suffix to any truncated
string value. `MessageBrowser` sets `bodyTruncated = true` on a browsed message
whose `text` (or any property value) ends with that marker, and reports the
observed approximate limit (`visibleLength − suffixLength`, ≈256 on 2.44) on the
single-message read. The detail panel renders a notice with that limit and
`BrokerXmlSnippets.forMessageBodyLimit()`'s `<address-setting>` snippet, plus the
line that faithful binary I/O is Phase 4. `MESSAGE_IO` stays the feature gate;
`BrokerCapabilities`, `CapabilityProbe`, and `ClusterViewMapper` are untouched.
`CapabilityLedger.tsx` is still mounted (Settings, and the cluster header when
any of the existing four capabilities is not `AVAILABLE`).

*Alternatives:* an observed three-state `MESSAGE_BODY_FULL` — rejected, it never
reaches a trustworthy `AVAILABLE` over Jolokia and duplicates the per-message
flag at connection level for a fifth `CapabilityAssessment` + mapper mapping +
spec delta. Show the truncated body with no marker — rejected outright by #5.

### D7 — Message operations target one node, explicitly (D1 depends on this)

Every message endpoint takes `?node={nodeId}`; when omitted, the service resolves
the message-holding live endpoint of the logical node serving that queue (via the
existing topology/aggregation layer) and echoes which node answered in the
response. A backup holds no messages, so "the live node" is unambiguous.

*Alternatives:* fan a browse across all endpoints — rejected, only one holds
messages; it would double the broker load for nothing.

### D8 — Mutations nudge SSE after commit; no new topic (ADR-0018, non-negotiable #9)

On success the service registers a
`TransactionSynchronization.afterCommit` that calls
`hub.publish(clusterId, "queues")`, so the grid refreshes at once instead of
waiting up to 15s for tier B. Publishing inside the transaction would signal
before commit. No `messages` / `audit` topic: the audit screen's existing 5s
`refetchInterval` is sufficient and there is no producer for a message-stream
event in Phase 3.

*Alternatives:* publish inline in the method — rejected, races the commit;
a dedicated `messages` topic — rejected, a topic with no producer and no poller.

### D9 — DLQ addresses are read from the broker, never inferred (ADR — none needed)

`DlqService` reads `getAddressSettingsAsJSON("#")` (or per-address) for
`deadLetterAddress` / `expiryAddress`, then lists the `queue_snapshot` rows on
those addresses. If the read fails the view returns an explicit
"configuration unavailable" state — it does **not** match `/DLQ/i`.

*Alternatives:* name heuristic fallback — rejected, it would silently mislabel a
queue that happens to be called `DLQ` and miss a real DLA with another name.

### D10 — OpenAPI snapshot from a test, not a build plugin (ADR-0019 status update)

Phase 2's blocker was that generation needed a running backend. Instead:
`OpenApiSnapshotTest` (MockMvc, `PostgresIntegrationTest`) does
`GET /v3/api-docs`, pretty-prints, writes `web/openapi.json` (committed).
`npm run gen:api` (`openapi-typescript web/openapi.json -o src/api/schema.d.ts`)
runs in `npm run build`. CI's existing `git diff --exit-code` step — already
there to catch unformatted Java — then fails on any undeclared contract drift for
free. `web/src/api/client.ts` is rewritten onto `components["schemas"][…]`; every
hand-written DTO interface and `assertShape` / `assertPaged` are deleted;
`ApiError` and `keys` stay.

*Alternatives:* `springdoc-openapi-maven-plugin` — rejected, it needs a server
lifecycle in the build, the exact thing that failed before; `openapi-fetch` /
`orval` — rejected (ADR-0019 already did), they replace the working `fetch`
wrappers and hooks.

### D11 — DOM test harness: Vitest + Testing Library + MSW (ADR-0024)

`vitest` (reuses the installed Vite/esbuild), `@testing-library/react` +
`user-event` + `@testing-library/jest-dom`, `jsdom`, `msw`. `vitest.config.ts`
with `environment: 'jsdom'` and a setup file. `npm test` joins `just verify-web`
and CI's frontend job (in neither today). The one `node:test` file
(`topology/layout.test.ts`) migrates; the two parked Phase 2 component tests are
written.

*Alternatives:* keep `node:test` + a hand-rolled jsdom global — rejected, no
`user-event`, no MSW, and CONTRIBUTING already promises Testing Library + MSW;
`happy-dom` — kept as the fallback only if `jsdom` won't install.

### D12 — Message UI is three routes, not a drawer (non-negotiable #9)

Browse needs a table + detail panel + filter + selection + action bar — more than
the right-hand `QueueDetailDrawer` holds. New routes on `ClusterLayout`'s tab
strip: `queues/$queueName/messages`, `dlq`, `audit`. `node` / `filter` / `page`
are URL search params with a hand-written validator in the
`validateResourceSearch` shape; message **selection** is ephemeral React
`Set<string>` keyed by `rowKey` (v9 `tableFeatures({})` keeps client features
off, so selection lives outside the table). `QueueDetailDrawer` keeps its
per-node breakdown and gains a "Browse messages" link. The two hand-rolled
typed-confirmation copies are extracted into `src/shared/ConfirmByTyping.tsx`
rather than adding a third and fourth.

*Alternatives:* expand the drawer — rejected, a bulk action bar + preview modal +
detail panel in a 480px drawer is cramped and fails the four-second-purpose test.

## Risks / Trade-offs

- **Slice 0 — resolved.** Ran first against the dev pair; findings in broker
  notes §11. `browse` returns a plain array (no double-decode);
  `browse(page, size, filter)` pages at the broker, capped at 200; truncation is
  the explicit `, + N more` marker; `MESSAGE_BODY_FULL` dropped (not probeable —
  ADR-0021, D6); by-filter ops return the affected count; `sendMessage` returns
  the new id; message filter numeric predicates (`AMQSize > 1000`) work; an
  invalid filter → 500 `AMQ229020`, mapped to a 400 by the service.
- **A queue deeper than `managementBrowsePageSize` (200)** cannot be *inspected*
  past row 200 except by filtering → acceptable: move/delete/retry are by-filter
  or by-id, so this limits inspection, not action. No `browse.max-rows` setting.
- **springdoc 3.1.0 vs Spring Boot 4.1** → confirmed built against the 4.1.0
  parent POM, but Phase 2 hit friction; bounded to slice 1. If it misbehaves,
  keep the Phase 2 fallback for the new DTOs and record it in ADR-0019 rather
  than fight it — slices 3+ are unblocked either way.
- **Vitest/esbuild postinstall under the env's allow-scripts policy** → Vite 8 is
  already installed and working so esbuild is present; fallback to `happy-dom` or
  keep `node:test`, recorded in ADR-0024. Bounded to slice 2.
- **Dry-run count races the real operation** (messages arrive/leave between the
  count and the act) → the number is labelled an estimate in the API and UI; the
  audit row records the broker's actual affected count on execution, which is the
  number of record.
- **`countMessages` / `browse` filter predicates** — Phase 2 §10 found numeric
  predicates (`GREATER_THAN`) unverified and `sortColumn` broken on 2.44 → slice
  0 re-checks `countMessages` with a filter on a queue with real depth; the
  by-filter UI only offers predicates slice 0 confirms.
- **Mutation holds a DB transaction across a broker call** (ADR-0015's carve-out)
  → the call is behind the per-node limiter and the 10s read timeout, and it is
  one POST; the atomicity of the audit row is worth the bounded hold, exactly as
  for `ClusterService`.

## Migration Plan

- **Schema:** none. `AuditEventEntity` gains `@Column` mappings for `request_id`,
  `source_ip`, `user_id` — columns already in changeset `004-audit.sql`;
  `ddl-auto: validate` already tolerates them. Rollback is a code revert.
- **Config:** `application.yml` gains `artemis-studio.safety.bulk-cap: 1000`,
  seeding `studio_setting`; absent config falls back to the packaged default.
  (No `browse.max-rows` — the broker paging cap covers it, per slice 0.)
- **Dependencies:** backend `+springdoc-openapi-starter-webmvc-ui:3.1.0`;
  frontend dev-only `+vitest`, `+jsdom`, `+@testing-library/*`, `+msw`,
  `+openapi-typescript`. All additive; rollback is removing them and restoring
  the hand-written types (kept in git history) — but the ADR-0019 rollout is the
  point, so this is forward-only barring the slice-1 risk above.
- **Deploy:** one image as always. `/v3/api-docs` + Swagger UI become reachable
  and unauthenticated — acceptable while the whole tool is unauthenticated
  (Phase 8), and already recorded in ADR-0019's consequences.
- **Rollout order:** slice 0 (spike, no product change) → slice 1 (types) →
  slice 2 (harness) → slices 3–9 (feature, each shippable) → slice 10 (cadence) →
  slice 11 (close-out + `/opsx:archive`). Slices 1 and 2 can land in either order;
  both precede 3+ so new DTOs are generated and new components are testable.
