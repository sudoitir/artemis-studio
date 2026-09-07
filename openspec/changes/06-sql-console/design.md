## Context

See `proposal.md` — Why. The constraints that shape the approach, all pre-existing:

- **Message reads already have exactly one shape.** `MessageTransport.browse(target,
  page, size, filter)` with two implementations (Jolokia, Core — ADR-0029), a
  `NodeCallLimiter` permit per call, and `queue_snapshot` as the only place that
  knows which queues exist on which node. This change adds a *caller*, not a new
  broker call.
- **The broker's filter is a JMS selector.** It evaluates headers and application
  properties. It cannot see the body. That single fact splits the whole design.
- **Jolokia truncates.** `management-message-attribute-size-limit` cuts bodies and
  properties, and the only signal is a literal `, + N more` marker (`MessageBrowser`).
  Core does not truncate but has no server-side offset, so `CoreMessageTransport`
  falls back to Jolokia past `BROKER_PAGE_CAP` — fidelity can change mid-query.
- **Postgres is declared disposable cache** (CLAUDE.md). The index qualifies that
  claim and needs its own ADR to do so.
- **SSE is the only realtime transport** (ADR-0003), one multiplexed stream per
  cluster. A tailed query does not fit that model.

## Goals / Non-Goals

**Goals:**

- One query language, two backends, with provenance carried in the data rather than
  reconstructed in the UI.
- Predicate pushdown as the primary performance mechanism, and the pushdown/scan
  split made visible to the operator *before* they run the query.
- Bounded, cancellable, rate-limited execution — the cost gate refuses rather than
  truncates.
- An editor good enough that an operator writes a correct query on the first try.

**Non-Goals:**

- A general SQL engine. The dialect is deliberately small enough to validate
  exhaustively against a whitelist.
- Query optimisation. There is one plan per query; the planner chooses a backend and
  splits predicates, and does nothing else.
- A complete message capture. See ADR on the sampled tail.
- Cross-cluster `FROM` in this change — the grammar reserves it, Phase 4 implements it.

## Decisions

### D1 — A restricted SQL dialect parsed with JSqlParser, validated on the AST

We will use **JSqlParser** (Apache-2.0, pure Java, no transitive dependencies) to
produce an AST, then validate that AST against a fixed `ColumnCatalogue` and a
whitelist of node types. Real SQL syntax means the frontend's SQL grammar,
highlighter and completion engine work unmodified.

Validation is on the **AST, never on the text**. A regex "no DROP" check is the
classic way to get this wrong; walking the tree and rejecting every node type not on
the whitelist cannot be talked around.

*Alternatives:* Apache Calcite — a full planner and optimiser for what is a
validate-and-rewrite job, an order of magnitude more weight and surface. A
hand-written parser (ANTLR or recursive descent) — buys a custom grammar we do not
need and loses the editor's off-the-shelf SQL support.

### D2 — The source is a schema qualifier

`FROM broker."ORDER.IN"` / `FROM index."ORDER.*"` / bare `FROM "ORDER.IN"`. This is
ordinary SQL, so the parser needs no extension, the editor completes it for free, and
`Table.getSchemaName()` hands us the choice. A `SOURCE broker` clause or a
`/api/.../sql?source=broker` parameter would both put the decision somewhere the
query text does not carry — and the query text is what gets pasted into a ticket.

Queue names must be double-quoted: `ORDER` is a SQL reserved word and `DLQ.$sys`
contains a `$`. The parse error for an unquoted target says exactly that.

### D3 — WHERE splits into pushdown and residual, by conjunct

The planner walks the top-level `AND` conjuncts. A conjunct is pushed down when
every column it names is a header or `props.*` **and** its operator has identical
semantics in JMS selector syntax. Everything else is residual. A disjunction
containing a residual leaf makes the whole disjunction residual — pushing down half
an `OR` changes the result set, which is the one bug in this area that produces
silently wrong answers rather than errors.

`ILIKE` and any case-insensitive comparison are residual: the selector has no such
operator, and an approximate translation is worse than a scan.

The selector is **rendered from the validated AST** — identifiers from the catalogue,
literals escaped by doubling `'`. Query text never becomes selector text. Artemis
validates it again anyway (`AMQ229020` → 400, already handled).

### D4 — Two executors behind one plan

`BrokerQueryExecutor` fans out over `MessageTransport.browse` on virtual threads
(`StructuredTaskScope`), one target per task, `NodeCallLimiter.acquire()` before each
call, residual predicate applied per page, stopping at the first bound reached.
`IndexQueryExecutor` compiles the same AST to a parameterised JDBC statement in a
read-only transaction with `statement_timeout`.

Both emit the same row shape with a `source` field. The UI has no branch on backend
beyond rendering the badge and the staleness notice.

Targets are de-duplicated by `CrossNodeAggregator.logicalKey` — a primary and its
synced backup are one logical node, and `queue_snapshot` holds a row per endpoint.
Skipping this returns every message twice on any HA pair.

### D5 — Cost is estimated from `queue_snapshot`, and the gate refuses

`queue_snapshot.message_count` is already scraped per queue per node. Estimated
examined messages = sum over targets, or `LIMIT` when every predicate is pushed down
(the broker stops early). Over the ceiling → `422` with the estimate and a narrowing
hint, before the first browse.

Refusing beats truncating: a truncated result is indistinguishable from a complete
one at a glance, and an operator during an incident will read it as "not there".

### D6 — Streaming results, not a batch response

Both static and live results go out over SSE (`GET .../sql/stream`). A static query
over 50 queues takes seconds; a batch response gives the operator a spinner and no
way to stop. Streaming gives per-node progress, partial frames as each node answers
or fails, and cancellation for free — closing the stream cancels the
`StructuredTaskScope`.

This is a **second** SSE endpoint, not a topic on the cluster stream: the payload
depends on parameters one client supplied, so it cannot be broadcast. That is the
`realtime-stream` spec delta.

### D7 — The tail is a poller, and it is the index writer

`SqlTailPoller` holds a high-water mark on `(timestamp, messageId)` per target and
re-browses at the configured interval. One mechanism serves both live mode and index
population — an index subscription is a tail with a persistent sink and no client.

Rejected: a Core consumer (destructive — it would eat the operator's messages); a
divert to a Studio capture queue (a true capture, but it mutates `broker.xml`-level
config and needs a cleanup guarantee we cannot make if Studio dies mid-flight;
revisit as its own change).

The consequence is a sample, not a capture. This is stated permanently in the UI, not
in a dismissable notice — see the honesty ADR.

### D8 — The index is opt-in, partitioned, and short-retention by default

`message_index_subscription` (the opt-in) + `message_index` (range-partitioned on
`observed_at`), GIN `jsonb_path_ops` on properties, GIN `gin_trgm_ops` on the body.
Partition create/drop copies `MetricPartitionMaintainer` exactly — same pattern,
same problem. Retention defaults to 7 days; dropping a subscription drops its
partitionable rows in bulk, not row by row.

Column order follows convention #7 (8-byte, then 4-byte, then uuid/boolean).

### D9 — CodeMirror 6, not Monaco

`@codemirror/lang-sql` accepts a `schema` object and produces table/column completion
from it — feed it live queue names from `queue_snapshot` plus the column catalogue and
completion is configuration, not code. ~150 KB against Monaco's multi-MB plus worker
plumbing that fights Vite. The editor is one pane of one view; it does not get to be
the largest thing in the bundle.

### D10 — `SELECT`-only, and actions route back through `MessageService`

No mutation is expressible. A result row's action menu calls the existing audited
message endpoints with the row's full `(node, queue, messageId)` identity. One path
to a destructive verb, already carrying dry run, bulk cap and audit.

## Risks / Trade-offs

- **A body-predicate query is a full scan of the target queues** → The cost gate
  refuses over the ceiling, the EXPLAIN strip states the estimate before the run, the
  scan cap bounds it, and every read takes a rate-limit permit. Worst case is a slow
  query, not a hurt broker.
- **Jolokia truncation makes a body predicate produce false negatives** → detected via
  the existing `bodyTruncated` flag and surfaced as "result may be incomplete", naming
  the limit and the Core channel. Cannot be fixed, only disclosed.
- **Core→Jolokia fallback changes fidelity mid-query** (`BROKER_PAGE_CAP`) → the per
  node channel is reported in the result, so a mixed-fidelity result says so.
- **The tail misses consumed messages** → stated permanently; where `MessagesAdded`
  deltas allow, the observed gap is reported as a number rather than left implied.
- **The index stores payload in Studio's database** → opt-in per queue, audited on
  creation, the creation dialog states what is stored, retention defaults short, one
  action drops it.
- **Clock skew silently empties a relative time window** → resolved through
  `ClockOffsetService` per node (ADR-0053); an unmeasured node is disclosed.
- **A new dialect is a new thing to learn** → in-page catalogue, completion over real
  queue names, worked examples that load into the editor, and errors that suggest the
  catalogue column you meant.
- **JSqlParser accepts more SQL than we support** → the whitelist is the contract, and
  its rejection path is unit-tested per construct (join, subquery, union, CTE,
  non-SELECT, unknown function, unknown column).

## Migration Plan

Additive throughout; nothing existing changes behaviour.

1. Phase 1 ships the broker backend. No schema change, no new table. Reversible by
   removing the route.
2. Phase 2 adds the tail. Still no schema change.
3. Phase 3 adds the Liquibase changeset for the index. New tables only — no existing
   table is altered, so `ddl-auto=validate` stays green and rollback is the
   changeset's own `--rollback`. With no subscription created, the tables stay empty
   and the console behaves exactly as in Phase 2.
4. Phase 4 is additive UI and grammar.

Rollback at any phase is the previous image; the index tables are droppable and hold
nothing the product needs to function.
