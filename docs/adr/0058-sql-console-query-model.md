# ADR-0058: The SQL Console is a restricted dialect, validated on the AST, and read-only

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: Artemis Studio maintainers

## Context

Studio browses one queue, on one node, one page at a time (ADR-0021). The question
an operator actually arrives with — "where did order 4471 go?" — spans queues and
cannot be expressed at all, because the broker's own filter is a JMS selector and a
selector cannot see a message body.

Three properties of the surface shape everything that follows.

**The selector is the only server-side filter there is.** Artemis evaluates it over
JMS headers and application properties. It has no access to the body, no
case-insensitive comparison, and no JSON path. A predicate the selector can take
costs nothing — the broker filters and returns fewer messages. A predicate it cannot
take costs a scan of every message the broker did return.

**A query is a load decision.** Studio's first non-negotiable is that it must never
be the reason a broker falls over. Every existing cross-node view reads
`queue_snapshot`; this one calls the brokers, potentially across dozens of queues, at
an operator's typing speed.

**Whatever we accept, we execute.** A console that takes free text and turns it into
a broker call is the place where an injection-shaped bug would live. Not a malicious
one — this is an internal tool behind authentication — but the ordinary kind, where
an operator's queue name containing a quote produces a selector that means something
other than what they typed.

## Decision

We will expose a **restricted SQL dialect**, parse it with **JSqlParser**, and
validate the resulting AST against a fixed catalogue.

**D1 — Real SQL syntax, a small subset.** `SELECT`, one `FROM`, `WHERE`, `ORDER BY`,
`LIMIT`, over a fixed column catalogue. Real syntax means the editor's off-the-shelf
SQL grammar, highlighting and completion work unmodified; the small subset means the
whitelist can be enumerated and tested exhaustively.

**D2 — Validation walks the AST, never the text.** Every node type not on the
whitelist is rejected: joins, subqueries, set operations, CTEs, any statement that is
not a `SELECT`, any function outside a documented list, any column not in the
catalogue. A regular expression asserting the absence of `DROP` is how this is
normally got wrong; a tree walk that rejects by default cannot be talked around by
casing, comments or whitespace.

**D3 — The source is a schema qualifier.** `FROM broker."ORDER.IN"` and
`FROM index."ORDER.*"` are ordinary SQL, so the parser needs no extension and the
editor completes them for free. A separate `?source=` request parameter would put the
choice somewhere the query text does not carry — and the query text is what gets
pasted into a ticket. Queue names must be double-quoted: `ORDER` is a reserved word
and `DLQ.$sys` contains a `$`.

**D4 — The `WHERE` clause splits by conjunct.** A top-level `AND` conjunct is pushed
down when every column it names is a header or an application property *and* its
operator means the same thing in selector syntax. Everything else is residual and is
evaluated by Studio over the messages the broker returned.

A disjunction containing a residual leaf is residual **as a whole**. Pushing down one
side of an `OR` narrows the set the other side gets to see, which changes the answer.
This is the one mistake in this area that produces a silently wrong result rather than
an error, so it is stated here rather than left to the implementation.

Case-insensitive comparison is residual. The selector has no such operator and an
approximate translation is worse than an honest scan.

**D5 — The selector is rendered from the validated AST.** Identifiers come from the
catalogue; literals are escaped by doubling `'`. Query text never becomes selector
text. Artemis validates the result again anyway (`AMQ229020`, already mapped to a
400), so a rendering bug fails loudly.

**D6 — Plan is an operation, and the gate refuses rather than truncates.** Planning
parses, validates, resolves targets against `queue_snapshot` and estimates cost
without contacting a broker. Over the configured ceiling the query is refused with
the estimate, the ceiling and a narrowing hint. A truncated result is
indistinguishable from a complete one at a glance, and an operator mid-incident reads
it as "not there".

**D7 — The console is `SELECT`-only.** No mutation is expressible in the dialect.
Acting on a result row routes back through the existing message operations, which
already carry a dry run, the ADR-0022 bulk cap and an audit record. A second path to
a destructive verb would be a second place to get the safety contract wrong.

## Consequences

- A body predicate is a scan, always, and no amount of implementation effort changes
  that. The product's answer is to make the cost visible before the query runs rather
  than to hide it.
- The dialect will look like SQL and not be SQL. An operator will eventually type a
  join. The rejection has to name the construct and be pleasant about it, because it
  is a routine event and not an error condition.
- Every column an operator can query has to be added to the catalogue deliberately.
  That is friction, and it is the point: the catalogue is the whitelist.
- Studio now issues broker reads at typing speed. The `NodeCallLimiter` permit on
  every browse and the cost gate before the first one are what keep non-negotiable #1
  true. Neither is decoration.
- Results are now bounded in four ways at once (targets, messages examined, rows
  returned, wall clock). Every bound has to be reported when reached or the product
  lies by omission.

## Alternatives considered

- **Apache Calcite.** A complete SQL parser, planner and optimiser. It would do the
  job, and it is an order of magnitude more dependency and surface than a
  validate-and-rewrite task needs. We do not want a planner; we want a whitelist.
- **A hand-written grammar (ANTLR or recursive descent).** Buys a custom syntax we do
  not want and loses the editor's free SQL support. The dialect's value is that it is
  familiar.
- **A structured filter builder instead of a language.** Safer by construction and
  unusable for the compound questions this feature exists to answer. It also does not
  paste into a ticket.
- **Translating body predicates into a selector approximation.** There is no honest
  approximation. A wrong answer during an incident is worse than a slow one.
