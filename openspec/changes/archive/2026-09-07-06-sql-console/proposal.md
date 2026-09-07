## Why

Studio can browse one queue, on one node, one page at a time. An operator holding a
support ticket that says "order 4471 never arrived" cannot answer it: they must guess
which of a hundred queues it landed on, page through each by hand, and express the
question as an Artemis JMS selector — which cannot see the message body at all, where
the order number actually lives.

Every other observability surface in Studio answers a question across the cluster.
Message search, the one an operator reaches for during an incident, does not exist.

## What Changes

**A SQL Console becomes a top-level per-cluster view.** One restricted-SQL dialect
queries messages across many queues at once, with an IDE-grade editor — syntax
highlighting, completion over the real queue names and the column catalogue, and the
syntax reference in the page rather than behind a docs link.

**Two backends, and the result always says which one answered.**

- The **broker** backend is current truth. Header and property predicates are
  translated into a native JMS selector and pushed down to the broker over the
  existing browse fan-out, so they cost nothing; body and JSON predicates are the
  residual, scanned in Studio under a cap. The console explains that split *before*
  the query runs.
- The **index** backend is an opt-in, per-queue, retention-bounded Postgres copy that
  answers for the past — including messages that have since been consumed, which the
  broker by definition cannot. An indexed row is labelled as such and carries a
  **Verify on broker** action that re-reads it from the live broker.

**Live mode tails a query.** New matches stream over SSE as they are observed. The
mechanism is repeated, rate-limited browse against a high-water mark, so it works on
every broker and mutates nothing — and it is therefore a *sample*, not a capture.
Messages consumed between polls are not seen. The UI says so permanently, because an
operator who believes a tail is complete will draw a false conclusion from an empty
result.

**The console is `SELECT`-only.** No mutation is expressible in the dialect. Acting on
a result row routes back through the existing audited message operations, so there is
exactly one path to a destructive verb and it already has a dry run, a bulk cap and an
audit record.

**A query is a broker-load decision, so it is gated like one.** The planner returns a
cost estimate — how many queues, how many nodes, whether a body scan is required — and
refuses a query above the configured ceiling with the reason and the way to narrow it,
rather than starting a fan-out it will silently truncate.

## Capabilities

### New Capabilities
- `sql-console`: the restricted SQL dialect, its column catalogue and source
  qualifiers, planning and cost estimation, selector pushdown versus residual
  evaluation, the two execution backends, result provenance and partial-result
  reporting, live tail semantics, and the console UI contract.
- `message-index`: the opt-in per-queue message index — subscription lifecycle, what
  is captured and what is not, retention and partition maintenance, the staleness
  contract on an indexed row, and the operator's ability to drop it.

### Modified Capabilities
- `realtime-stream`: the spec currently states that a cluster has one multiplexed SSE
  stream. A tailed query is per-query subscription state, not a cluster-wide broadcast,
  so it needs a second, separately-scoped stream endpoint. The spec must say when a new
  stream is warranted rather than a new topic.

## Impact

- **Studio gains its first read path that fans out over messages rather than metadata.**
  Every existing cross-node view reads `queue_snapshot`; this one calls the brokers. The
  per-node rate limiter, the cost gate and the scan cap are what keep non-negotiable #1
  ("Studio must never be the reason a broker falls over") true, and they are not
  optional decorations on the feature — they are the feature's contract.
- **The index stores message payload in Postgres.** That qualifies the standing rule
  that Studio's Postgres holds only disposable broker-derived cache: the index is
  disposable and rebuildable in the same sense, but it is also searchable payload and
  therefore a data-retention concern. It is off by default, per queue, audited on
  creation, short-retention by default, and droppable in one action.
- **Result truth is now layered.** A row may be current, or observed-and-possibly-gone.
  Rendering the two identically would be the single most damaging thing this feature
  could do, so provenance is carried in the DTO, not inferred in the UI.
- Backend: one new dependency (JSqlParser). New `sql/` package; new Liquibase changeset
  for the index; new `sql:` configuration block with the caps.
- Frontend: CodeMirror 6 (editor, SQL language, autocomplete). New `web/src/sql/` view,
  a nav entry, and index-subscription management under Settings.
- ADRs: the query model and the SELECT-only boundary; the index as an opt-in copy; the
  sampled-tail honesty contract.
- Not in scope: mutating statements, cross-cluster `FROM` (the grammar reserves it),
  divert-based true capture, and scheduled or alerting queries. Each turns an operator
  action into standing load.
