# Tasks

Layered so the product works end to end at the end of every group. Do not start a layer
before the one above it is green. See `design.md` — Migration Plan.

## 1. Groundwork

- [x] 1.1 Write ADR-0062 (message capture is a divert into a ring-bounded Studio queue; address-scoped, per-node, instance-owned), marking ADR-0059 and ADR-0060 superseded with a link and leaving their decisions untouched
- [x] 1.2 Write ADR-0063 (Postgres full-text over the message index, `simple` configuration)
- [x] 1.3 Write ADR-0064 (query execution is POST-then-stream; query text leaves the URL)
- [x] 1.4 Delete `openspec/changes/04-divert-and-bridge-management/`, confirming first that every requirement in its four spec deltas appears in this change

## 2. L1 — fix what is broken today

- [x] 2.1 `MessageIndexPartitionMaintainer`: retention already covers disabled subscriptions (`findAll()`, not `findByEnabledTrue()`) — no defect. Added the regression test that pins it, since a refactor to `findByEnabledTrue()` would look like a tidy-up and silently shorten a disabled subscription's payload
- [x] 2.2 Void — no defect. Verified against Postgres 17 that `gin_trgm_ops` serves `ILIKE` (`~~*`) as a Bitmap Index Scan, so the bare `ILIKE` is already indexed and no `lower(body)` index is warranted
- [x] 2.3 `MessageIndexCapture.reconcile()`: record and surface start failures instead of swallowing them at `log.debug`
- [x] 2.4 `SqlConsoleView`: wrap the Live tail `Switch` in `CapabilityGate` so its disabled reason is keyboard-reachable
- [x] 2.5 `useSqlTail`: bound the accumulated row list, state when the oldest rows are being discarded, and stop `timers.current` growing for the life of the stream
- [x] 2.6 `SqlConsoleView`: do not fire `useSqlPlan` when the gate is blocked; state the queue-completion bound when it truncates at its limit
- [x] 2.7 Added `SqlControllerTest` (7 tests) and `SqlStreamControllerTest` (4 tests) — there were none. Auth is already covered generically by `EndpointProtectionTest`
- [x] 2.8 `just verify` green

## 3. L2 — routing read view

- [x] 3.1 `broker/routing/DivertOperations` — list, create, destroy over `JolokiaBrokerClient` using the single-String JSON-configuration overloads; `NodeCallLimiter` permit per call; `managementWrite` gated; refusals recorded through `CapabilityLedger`
- [x] 3.2 `broker/routing/DivertRow` and the bridge row, on the `broker/QueueRow` pattern
- [x] 3.3 Make no origin claim per ADR-0065: no marker exists on `DivertControl` and no management operation returns configured-but-undeployed diverts. Mark the diverts Studio owns from Studio's own records; every other divert is listed without an origin statement
- [x] 3.4 Cross-node divert and bridge views through the existing resource-view mechanism, with the same paging, filtering, node attribution and URL state
- [x] 3.5 `divert:write` permission; create and delete fanned out across live nodes with per-node outcomes and a preview that changes nothing
- [x] 3.6 Routing screen: direction legible, exclusivity distinguishable in text, filterable by address, Studio-owned diverts marked in every listing with the `broker.xml` that removes the drift attached
- [x] 3.7 MCP: diverts and bridges through the existing resource-kind discriminator; divert mutation as one guarded tool whose result states the configuration drift it creates
- [x] 3.8 `just verify` green

## 4. L3 — capture on a single node, anycast

- [x] 4.1 Per-cluster Postgres advisory lock, with lock loss handled as "stop reconciling", not as an error
- [x] 4.2 Liquibase changeset `022-message-capture.sql`: `message_capture_node`; `mode`, `ring_size`, `filter_string`, `max_bytes`, `max_rate`, `body_cap_bytes` on `message_index_subscription`; `origin`, `orig_address`, `source_message_id`, `body_truncated` on `message_index`; backfill existing rows as `SAMPLED`. Column ordering per non-negotiable #7; new changeset only, never an edit to a released one
- [x] 4.3 `broker/capture/CaptureTap` — preflight (exclusive divert on the source address; authority to set the security setting), then address settings, security setting, ring-bounded non-durable capture queue, non-exclusive divert. Idempotent install and removal
- [x] 4.4 `broker/capture/CaptureReconciler` — desired from subscriptions, actual from `listDivertNames` filtered to this instance's prefix; create missing, destroy own orphans, never touch another instance's; act on drift only
- [x] 4.5 `broker/capture/CaptureConsumer` — Core `MessageListener` per capture queue over the existing `CorePool`, batch-acked after the sink commits; reuse the existing `BrowsedMessage` mapping, do not write a second message mapper
- [x] 4.6 `broker/capture/CaptureBus` — fan-out to tail, index writer and request-reply sink
- [x] 4.7 Resolve the original address from Studio's own capture-queue mapping, and the source message ID from `_AMQ_ORIG_MESSAGE_ID`; null when absent, with `VerifyOnBroker` offered-but-disabled and the reason stated
- [x] 4.8 `capture:write` permission; audit every install and removal before the broker call, updated with the outcome
- [x] 4.9 Capture creation and deletion API, with deletion stating what it will destroy
- [x] 4.10 `CaptureTapTest` (install refused with a named reason when an exclusive divert exists; refused when the security setting cannot be set) and `CaptureReconcilerTest` (orphan destroyed, missing created, another instance's divert untouched, idempotent across passes)
- [x] 4.11 End to end on `just dev-up`: produce N messages on a captured anycast queue and consume them immediately; assert N rows with `origin='CAPTURED'`, found by the **original** queue name
- [x] 4.12 `just verify` green

## 5. L4 — capture on a real cluster

- [x] 5.1 Assert the tap on every live node each pass; per-node state in `message_capture_node` with the reason for any node not capturing
- [x] 5.2 Record the per-node coverage window, including the interval a promoted backup was live and uncaptured
- [x] 5.3 Multicast: capture the address, and emit the address-scoped notice on any result whose queue is one of several bound to it
- [x] 5.4 `broker/capture/CaptureLoss` — drop estimate from the `queue_snapshot.messagesAdded` delta minus rows recorded, reusing `TailStatus`; degrade the subscription rather than reporting it healthy
- [x] 5.5 Per-subscription ingest rate cap and batched writes, so backpressure does not degenerate into total loss
- [x] 5.6 Per-message body cap applied at the consumer, reusing the existing `BODY_TRUNCATED` disclosure; oversized bodies never transferred whole
- [x] 5.7 Per-subscription stored-size bound, with degradation reported when reached
- [x] 5.8 Optional capture filter, passed through as the divert's `filterString`
- [x] 5.9 `QueryPlanner.resolveSource` and coverage: per-node and partial, naming the nodes it cannot answer for
- [x] 5.10 Request-reply: observe captured addresses through `CaptureBus`, dedupe against samples, keep sampling for uncaptured addresses, and state that temporary reply queues are notification-derived
- [x] 5.11 Failover test on `just dev-up`: kill the primary, assert the tap is installed on the promoted backup and the coverage gap is recorded honestly
- [x] 5.12 Restart test: kill Studio mid-capture, restart, assert convergence without duplication; then delete the subscription and assert divert, capture queue, address setting and security setting are all gone
- [x] 5.13 `just verify` green

## 6. L5 — full-text over the index

- [x] 6.1 Functional GIN index `to_tsvector('simple', body)` in the changeset; index only text-ish bodies
- [x] 6.2 `MATCH (body) AGAINST ('terms')` in `ColumnCatalogue` and `SqlQueryParser` — the two-argument `MATCH(body, 'terms')` spelling cannot be parsed: `MATCH` is reserved by the SQL grammar for `MATCH … AGAINST`, measured against JSQLParser, and the `AGAINST` form is the grammar's own, compiled to `websearch_to_tsquery` in `IndexQueryExecutor`, index-only with the existing refusal wording
- [x] 6.3 `ORDER BY match_rank` via `ts_rank_cd`, refused with its reason when no `MATCH()` is present
- [x] 6.4 `origin`, `origAddress`, `sourceMessageId` as queryable index-only columns
- [x] 6.5 Mirror every addition into `web/src/sql/catalogue.ts` and `SyntaxHelp.tsx`
- [x] 6.6 `IndexQueryExecutorTest` — phrase, exclusion, ranking, and an `EXPLAIN` assertion that the functional GIN index is actually chosen
- [x] 6.7 `just verify` green

## 7. L6 — the console

- [x] 7.1 `POST /sql/query` returning a short-lived reference; `GET /sql/stream` by reference; query text out of every URL, `useSqlTail` updated
- [x] 7.2 Stop control for a running static query, not only for a tail
- [x] 7.3 Layout: resizable editor pane above a results pane that owns the remaining viewport height; pane size in `localStorage`, not the URL
- [x] 7.4 Replace the stacked alerts with one result meta bar plus a disclosure, preserving every statement and keeping the rows in view
- [x] 7.5 Tail banner states the captured claim with its per-node coverage when every target is captured, and the sampling claim otherwise; never the same wording for both
- [x] 7.6 Per-row provenance for captured versus sampled rows, in text
- [x] 7.7 Query history in `localStorage` — text, when, row count, source; reload into the editor without executing
- [x] 7.8 Column show/hide/reorder, and CSV/JSON export of the current rows
- [x] 7.9 Tail pause/resume with a buffered count and auto-scroll lock
- [x] 7.10 Inline editor error decoration at `ExplainStrip`'s `offending` token
- [x] 7.11 Capture subscription management: create dialog stating the full blast radius and the configuration equivalent; per-node state, held against both bounds, loss; delete via `shared/ConfirmByTyping.tsx`
- [x] 7.12 Keyboard-only pass asserted in tests for capture create and capture delete — focus enters the dialog, escape dismisses, focus returns to the trigger
- [x] 7.13 Contrast measured in both colour schemes for any new token; `prefers-reduced-motion` honoured
- [x] 7.14 `just verify` green

## 8. Documentation

- [x] 8.1 `docs/architecture.md` — add message capture, the message index and the SQL Console; the document currently stops at Phase 3/4 language and describes neither
- [x] 8.2 `README.md` — remove "Divert and bridge management" from the roadmap; rewrite the SQL section's "opt-in retention-bounded index" sentence, which now understates what ships
- [x] 8.3 Docs site `guide/sql-console`, plus a capture page covering what is created on the broker and how to remove it
- [x] 8.4 `docs/dockerhub.md` if the run instructions change
- [x] 8.5 Commit messages carry the changelog — call out the capture opt-in and the `capture:write` permission for upgraders, and mark the `message_index` provenance change as breaking
