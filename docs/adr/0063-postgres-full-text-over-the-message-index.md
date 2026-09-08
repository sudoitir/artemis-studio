# ADR-0063: Full-text search over the message index is Postgres, with the `simple` configuration

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: Artemis Studio maintainers

## Context

The message index holds message bodies so the console can answer "where did order 4471 go"
after the broker has forgotten. The only body search it offers is `LIKE '%fragment%'`, served
by a trigram GIN index (ADR-0059, changeset `021`).

Substring matching is the right primitive for an identifier and the wrong one for everything
else. It cannot express a phrase, cannot exclude a term, cannot rank, and matches across word
boundaries — `LIKE '%order%'` hits `reorder` and `borderline`. An operator mid-incident wants
"this phrase, not that term, best matches first", which is the shape of a search engine and
not the shape of `LIKE`.

ADR-0059 already considered and rejected an external search store: "a second system to deploy,
secure and operate for a self-contained tool whose entire packaging story is one container and
a Postgres", with a note to revisit if per-queue volume made Postgres the wrong shape. Capture
(ADR-0062) raises volume, so the note is due — and the answer is unchanged, because what
operators actually want from Elasticsearch here is phrases, exclusion and ranking, and
Postgres has all three.

## Decision

We will provide full-text search over `message_index.body` using Postgres text search, with
the **`simple`** configuration, exposed through the dialect as `MATCH()` and `match_rank`.

**D1 — A functional index, not a column.** `CREATE INDEX ... USING GIN
(to_tsvector('simple', body))`. No generated column, no backfill of existing rows, no change to
the write path, and it cascades to the partitions of the range-partitioned table. The query
must use the identical expression, which the executor renders from the catalogue rather than
from operator text, so it cannot drift.

A stored generated column would let a query name the column directly. It would also add a
column to every existing row, on a table this feature is about to make much larger, to save an
expression the executor writes anyway.

**D2 — The `simple` configuration, not `english`.** Message bodies are identifiers, JSON, order
numbers and status codes. English stemming folds `orders` to `order`, discards a stop-word list
written for prose, and applies none of it predictably to machine data. The retrievals this
feature exists for — find this id, find this correlation key — are the ones stemming damages.
`simple` lowercases and splits into tokens and stops there, which is what a log search wants.

**D3 — `websearch_to_tsquery` is the operator-facing syntax.** It accepts quoted phrases, a
leading `-` to exclude, and `or`, and it never raises a syntax error on arbitrary input. An
operator can type what they would type into a search box, and a stray quote produces a query
rather than a 400. `to_tsquery` would require us to build and escape an expression from
operator text, which is the injection-shaped surface ADR-0058 D5 exists to avoid.

**D4 — Ranking is opt-in and refuses rather than lying.** `ORDER BY match_rank` is available
only when the query contains `MATCH()`, and is refused with that reason otherwise. Ranking a
result set that has no relevance signal would return an arbitrary order under a name that
claims meaning.

**D5 — Full-text is index-only.** A live broker has no such facility, and there is no honest
approximation over a JMS selector. `MATCH()` against `broker.` is refused, naming the predicate
and the reason, reusing the catalogue's existing `indexOnly` mechanism and its notice rather
than inventing a second vocabulary for the same idea (ADR-0058 D2).

**D6 — Only text-ish bodies are indexed.** `to_tsvector` over base64 or binary produces
thousands of meaningless tokens per row and inflates the index for no retrieval value. Bodies
that are not text are stored and shown through the existing hex dump; they are not tokenised.

**D7 — Substring search stays as it is.** Trigram `LIKE` remains, because an operator
searching for a fragment inside an identifier needs it and no tokeniser will serve it. Both
`LIKE` and `ILIKE` are already served by the existing `gin_trgm_ops` index — pg_trgm's GIN
opclass indexes `~~` and `~~*` alike — so nothing about the substring path changes and no
second trigram index is added. A `lower(body)` index would be a third GIN index on the
hottest write path, buying nothing.

## Consequences

- Two GIN indexes now sit on the index's hottest write path — trigram and full-text. GIN
  inserts are expensive, and this is a direct cost on capture throughput. It is why ADR-0062 D9
  requires batched writes; the two decisions are load-bearing for each other.
- Two body search idioms exist, and an operator has to know which to reach for. `MATCH()` for
  words and phrases, `LIKE` for a fragment inside a token. The console's syntax help has to
  teach the difference, because guessing wrong returns an empty result rather than an error.
- `simple` means no stemming, so a search for `orders` does not find `order`. For prose payloads
  that is a worse answer than `english` would give. We are choosing the payloads this product
  actually sees over the ones it might.
- The text search configuration is now part of the on-disk index definition. Changing it later
  is a reindex, not a setting.
- Postgres remains the only datastore. The packaging story — one container and a Postgres —
  survives the volume increase that capture brings.

## Alternatives considered

- **Elasticsearch or OpenSearch.** The real answer at a scale this product does not target, and
  a second system to deploy, secure, operate and back up. Rejected by ADR-0059 on those grounds;
  revisited here because capture changes the volume, and rejected again because the capabilities
  operators actually reach for are all present in Postgres.
- **Keep trigram only.** Cheapest, and it cannot express a phrase, an exclusion, or an order.
- **`english` (or a configurable) text search configuration.** Better for prose, worse for
  identifiers, and making it configurable turns a wrong answer into a wrong answer that varies
  by deployment and cannot be reproduced from a pasted query.
- **A stored generated `tsvector` column.** Marginally faster to plan, and it rewrites every
  existing row and widens the table this change is already growing.
