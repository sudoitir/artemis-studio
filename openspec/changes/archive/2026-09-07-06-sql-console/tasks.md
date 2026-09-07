## 1. Decisions of record

- [x] 1.1 Write `docs/adr/0058-sql-console-query-model.md` — the restricted dialect, JSqlParser with AST-level validation, the source-as-schema-qualifier, the pushdown/residual split by conjunct, and why the console is `SELECT`-only
- [x] 1.2 Write `docs/adr/0059-message-index-is-opt-in-and-disposable.md` — the index qualifies CLAUDE.md's "Postgres holds only disposable cache"; opt-in, audited, retention-bounded, droppable
- [x] 1.3 Write `docs/adr/0060-sampled-tail-is-not-a-capture.md` — why polling and not a Core consumer or a divert, and the permanent disclosure that follows
- [x] 1.4 Add the three ADRs to `docs/adr/README.md`

## 2. Dialect and planner (backend, no I/O)

- [x] 2.1 Add the JSqlParser dependency to `pom.xml`
- [x] 2.2 `sql/ColumnCatalogue.java` — the fixed column set (identity, headers, body, `props.*`, index-only), each column's type, whether it is pushdown-eligible, and a did-you-mean over the catalogue
- [x] 2.3 `sql/SqlQueryParser.java` — JSqlParser wrapper; walk the AST and reject every construct off the whitelist (non-SELECT, join, subquery, union, CTE, unwhitelisted function, unknown column) with the offending token named
- [x] 2.4 `sql/QueryAst.java` — the validated internal form the rest of the package consumes, so nothing downstream touches JSqlParser types
- [x] 2.5 `SqlQueryParserTest` — one rejection case per whitelisted-out construct; an unquoted `ORDER` target reports that queue names must be quoted; an unknown column suggests the near match
- [x] 2.6 `sql/SelectorRenderer.java` — pushdown conjuncts → JMS selector text, identifiers from the catalogue, literals escaped by doubling `'`
- [x] 2.7 `SelectorRendererTest` — quote escaping, `IN`, `IS NULL`, `LIKE` escapes; assert a body predicate and a case-insensitive comparison never reach the selector; assert a disjunction containing a residual leaf is not partially pushed down
- [x] 2.8 `sql/PredicateSplitter.java` — split top-level `AND` conjuncts into pushdown and residual by the D3 rule
- [x] 2.9 `sql/MessagePredicate.java` — residual conjuncts → `Predicate<BrowsedMessage>`, including body substring, JSON path extraction, and case-insensitive comparison
- [x] 2.10 `sql/QueryPlanner.java` — resolve `FROM` (including Artemis wildcards) against `QueueSnapshotRepository`, de-duplicate targets by `CrossNodeAggregator.logicalKey`, filter to the caller's cluster scope, choose the backend, estimate cost from `queue_snapshot.message_count`, produce a `QueryPlan`
- [x] 2.11 Normalise relative time predicates through `ClockOffsetService` per target node; mark a node whose offset is unmeasured
- [x] 2.12 `QueryPlannerTest` — wildcard resolution, HA-pair de-duplication, backend choice with and without the source qualifier, a `FROM` matching no queue, a queue excluded by permission being named rather than dropped, cost estimate with and without a residual

## 3. Broker execution and the static query API

- [x] 3.1 Add the `sql:` block to `ArtemisStudioProperties` — `maxTargets`, `scanCap`, `maxRows`, `timeout`, `maxConcurrentQueries`, `costCeiling` — and expose the overridable ones through `SettingsService`
- [x] 3.2 `sql/BrokerQueryExecutor.java` — `StructuredTaskScope` fan-out over `MessageTransport.browse`, `NodeCallLimiter.acquire()` per call, residual predicate per page, stop at the first bound reached and report which bound
- [x] 3.3 Per-node outcome collection: answered / failed-with-reason / channel-served-by, so a partial result names the node and the reason
- [x] 3.4 Detect a body predicate evaluated against a truncated body (`BrowsedMessage.bodyTruncated`) and mark the result possibly incomplete, naming the limit and the Core channel
- [x] 3.5 Report a per-node channel change when `CoreMessageTransport` falls back to Jolokia mid-query
- [x] 3.6 `web/dto/SqlViews.java` — plan view, row view (carrying node, queue, messageId, source, observedAt), per-node outcome view, bound-reached view
- [x] 3.7 `web/SqlController.java` — `POST .../sql/plan` (parse, validate, cost; contacts nothing) and `POST .../sql/query`; guard with `ClusterAccessGuard.requireCluster(..., MESSAGE_READ)`; refuse over the cost ceiling with a `422` carrying the estimate, the ceiling and a narrowing hint
- [x] 3.8 Audit every executed query as `sql.query` with the text, resolved target count, answering source and row count; register the action in the audit catalogue
- [x] 3.9 Cap concurrent queries per actor
- [x] 3.10 `BrokerQueryExecutorTest` against a mocked transport — the scan cap, the row cap, the timeout, a failed node producing a partial result, and cancellation stopping further reads

## 4. Console UI — static query

- [x] 4.1 Add CodeMirror 6 (`codemirror`, `@codemirror/lang-sql`, `@codemirror/autocomplete`) to `web/package.json`
- [x] 4.2 `web/src/sql/QueryEditor.tsx` — CodeMirror with the SQL language, a `--as-*`-derived theme honouring both colour schemes, and a `schema` built from live queue names plus the column catalogue
- [x] 4.3 Debounced call to `.../sql/plan` for inline error reporting while typing, without running the query
- [x] 4.4 `web/src/sql/ExplainStrip.tsx` — source, target and node counts, which predicates are pushed down, whether a scan is required, and the estimate, rendered before the query runs
- [x] 4.5 `web/src/sql/ResultGrid.tsx` — wraps the existing `grid/VirtualTable.tsx`; per-row source badge, node and queue attribution, tabular figures on numeric columns
- [x] 4.6 Render all four outcomes: pending with per-node progress, succeeded, failed with cause and next action, and partial via `shared/NodeOutcomeSummary.tsx` — its layout was extracted into an exported `OutcomeSummary` primitive so a query's per-node result reuses the shape without borrowing the lifecycle vocabulary, which would have reported a fan-out read as "applied to all nodes"
- [x] 4.7 Distinguish filtered-empty, no-queue-matched, and unreachable-node from a genuine empty result
- [x] 4.8 `web/src/sql/SyntaxHelp.tsx` — in-page modal with the column catalogue, the source qualifiers, the pushdown rules and worked examples that load into the editor in one action; keyboard-reachable, returns focus to its trigger
- [x] 4.9 `web/src/sql/SqlConsoleView.tsx` — the three-pane layout, `CapabilityGate` on `messageIo` (visible-and-disabled with the `broker.xml` snippet, never hidden)
- [x] 4.10 Row detail reusing `messages/MessageDetailPanel.tsx`; row actions route to the existing audited `MessageService` endpoints carrying the full `(node, queue, messageId)` identity
- [x] 4.11 `router.tsx` — `sqlRoute` at `clusters/$clusterId/sql` with `validateSqlSearch` (`q`; `live` arrives with the tail in group 5 — `source` is deliberately **not** a parameter, because it is the `FROM` qualifier inside `q` and a second owner of one fact is how the two drift apart); `app/navItems.ts` — entry after Queues
- [x] 4.12 Regenerate `web/src/api/schema.d.ts` via `npm run gen:api`
- [x] 4.13 Frontend tests — query by role and accessible name: the EXPLAIN strip states pushdown versus scan; a partial result renders `NodeOutcomeSummary` rather than an empty state; the help modal is keyboard-reachable and restores focus; the query survives a reload from the URL

## 5. Live tail

- [x] 5.1 `sql/SqlTailPoller.java` — per-target high-water mark on `(timestamp, messageId)`, re-browse at the configured interval, apply the plan, emit matches; registered with `DynamicSchedules`
- [x] 5.2 Estimate and report the observed gap from `MessagesAdded` deltas versus rows seen
- [x] 5.3 `web/SqlStreamController.java` — `GET .../sql/stream`, `SseEmitter` modelled on `StreamController` (same permission check, heartbeat and subscriber release); unsubscribe the poller on completion, timeout and error
- [x] 5.4 Move static execution onto the same stream so progress, partial frames and cancellation are shared with the tail
- [x] 5.5 `web/src/sql/useSqlTail.ts` — `EventSource` subscription and teardown on unmount. Deliberately **no** automatic reconnection: the static run rides the same stream, so reopening it would re-run an audited broker fan-out the operator did not ask for twice (design risk 11). A drop is reported as `disconnected` with a Run-it-again action.
- [x] 5.6 Live-mode rendering — pinned streaming header, new rows entering at the top with a highlight that honours `prefers-reduced-motion`, a running counter, and the non-dismissable sampled-tail notice
- [x] 5.7 Tests — the sampled-tail notice is present and cannot be dismissed; disconnecting stops the poller

## 6. The message index

- [x] 6.1 `db/changelog/changes/021-message-index.sql` — `pg_trgm`; `message_index_subscription` (queue pattern, interval, retention, `capture_from`, enabled); `message_index` range-partitioned on `observed_at`; GIN `jsonb_path_ops` on properties and `gin_trgm_ops` on the body; column order and per-table autovacuum settings per convention #7
- [x] 6.2 JPA entities and repositories for both tables
- [x] 6.3 `persist/MessageIndexPartitionMaintainer.java` — partition creation and retention drop, copying `MetricPartitionMaintainer`. Retention is per queue rather than per subscription: a partition holds every subscription's rows, so it is dropped only once it is past the longest retention in the estate, and anything that outlives its own queue's promise is removed first by a bulk range delete. The default partition is reaped too — it is never dropped, so rows written before the maintainer first ran would otherwise be kept forever
- [x] 6.4 Give `SqlTailPoller` an index-writing sink, so one mechanism serves both live mode and capture; record `last_seen_at` on re-observation. A subscription is literally the query `SELECT * FROM broker."<pattern>"`, tailed forever (`sql/MessageIndexCapture.java`), so broker load, permits and marks are the tail's, unchanged. `Listener.observed` is the new hook: `row` is only what has not been delivered before, which is the wrong event for a capture that needs to record a message being seen again
- [x] 6.5 `sql/IndexQueryExecutor.java` — validated AST → parameterised JDBC in a read-only transaction with `statement_timeout`; binds only, never interpolation
- [x] 6.6 Coverage checks — a window predating `capture_from`, a window past retention, and an uncovered queue in a wildcard `FROM` each produce a stated warning rather than a short result (`MessageIndexCoverageTest`)
- [x] 6.7 `POST .../sql/verify` — re-read one indexed row from the live broker and report present or gone. Three verdicts, not two: Artemis has no filter attribute for the internal message id, so the read is one page filtered on the message's enqueue timestamp, and when that page comes back full without the id the answer is UNKNOWN. Reporting a message as consumed on the strength of a bounded read is the false certainty this feature exists to remove
- [x] 6.8 `web/SqlIndexController.java` — subscription CRUD gated on `SETTINGS_WRITE`, audited, reporting per-subscription message count and payload bytes held
- [x] 6.9 Frontend — source badges, the "indexed, may have been consumed since" notice, the Verify on broker action, and subscription management in `SettingsView` with `ConfirmByTyping` on delete stating how many captured messages will be destroyed
- [x] 6.10 The subscription creation dialog states that message bodies will be stored for the chosen retention period, before it can be confirmed
- [x] 6.11 `IndexQueryExecutorTest` against Testcontainers Postgres — GIN and trigram predicates, coverage warnings, and a message still found after consumption; `MessageIndexPartitionMaintainerTest` covers the partition split and the retention reclaim

## 7. Verify

- [x] 7.1 `just fmt` then `just verify` — Spotless, Liquibase against Testcontainers Postgres, 522 backend tests, `verify-web` (187 frontend tests, tsc, eslint, vite build)
- [x] 7.2 End to end on `just dev-up`: send a few hundred messages to `ORDER.IN`; run a header-only query and confirm the plan reports pushdown; run a body query and confirm the estimate and the scan cap; tail while producing and confirm the notice; enable an index subscription, consume the queue, re-query `FROM index."ORDER.IN"` and confirm the rows are still found, labelled indexed, and that Verify on broker reports gone.
      Run against the two-node dev pair over the product's own API: 300 messages produced; the
      header-only plan reported `JMSPriority > 3` pushed down with no scan; the body predicate
      reported a scan over 300; the stream delivered rows, a per-node `ANSWERED · servedBy CORE`
      frame and a `done` carrying `ROW_LIMIT`; a subscription captured all 300 within one poll
      and reported 138,896 payload bytes; the queue was then drained and the same messages still
      answered from the index with `observedAt`/`lastSeenAt`; Verify reported **GONE** for a
      consumed message and **PRESENT** for a live one; the tail delivered 14 rows in one poll
      while a producer ran; deleting the subscription destroyed 326 captured messages and wrote
      the audit record that says so. Two defects were found and fixed by this walkthrough: a new
      subscription captured nothing that was already on the queue (the capture tail now starts at
      the beginning rather than at "now"), and a query that reached its row limit was recorded in
      the audit log as a **failure** rather than as the ordinary bounded query it is
- [x] 7.3 Confirm a query against a paired primary/backup cluster returns each message once — `QueryPlannerTest.aPairedPrimaryAndBackupCountOnce`, and confirmed live on the dev pair: both endpoints carry one Artemis node id, and the plan resolved to a single target
- [x] 7.4 Check contrast for the editor theme and the source badges in both colour schemes against the 4.5:1 floor.
      Measured: editor tokens 6.96 / 9.57 / 5.12:1 on the dark ground and 5.02 / 5.00 / 7.12:1 on
      white; body text 9.67:1 and 21:1. The live-tail row tint was cut from 0.16 to 0.12 alpha in
      the dark scheme — at 0.16 dimmed text over it measured 4.21:1, under the floor; it is now
      4.56:1 with body text at 7.97:1. One finding left alone as pre-existing and estate-wide:
      `--as-text-dimmed` is 3.32:1 on white (Mantine's own dimmed), so every screen's secondary
      text is under the floor in the light scheme. That is a token change for the whole product,
      not for this change to make on the way past
