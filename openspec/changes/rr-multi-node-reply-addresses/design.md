## Context

Request-reply tracing was designed against the two textbook reply patterns: a temporary
reply queue named by the request's `replyTo`, and one shared reply queue named in the
expectation. A third pattern is common in clustered deployments and was not covered: **one
shared reply queue per application instance**, so the reply address is chosen by whichever
responder picked the request up. The dev cluster this change is verified against uses
exactly that shape.

## Goals / Non-Goals

- **Goals.** Trace a request-reply exchange whose replies are spread over several known
  shared reply queues; sample every node that can serve the traced addresses; make the
  reply-address requirement visible before an operator has waited for flows that can never
  appear.
- **Non-goals.** Wildcard or pattern-matched reply addresses; per-reply-address deadlines
  or sample rates; changing the six flow states, the deadline resolution order, or the
  notification channel.

## Decisions

### D1 — A Postgres array, not a child table

`reply_addresses TEXT[]` on `rr_expectation` rather than an `rr_expectation_reply` child
table. The set is small (single digits), always read whole with its parent, never queried
independently, and never joined to. A child table would add a second query on the
correlator's hot path — `expectationFor` already re-reads every expectation of the cluster
per observation — for no read this feature performs. Hibernate maps `TEXT[]` natively as
`List<String>`; `ddl-auto=validate` is satisfied by the Liquibase-declared type.

Column placement follows the schema convention: `reply_addresses` is a 4-byte-aligned
varlena and goes with `request_address` and the other text columns, before the uuid and
boolean tail.

### D2 — An empty set means "temporary reply queue", not "unset"

The column is `NOT NULL DEFAULT '{}'`. Nullable would give two spellings of the same state
and force every reader to handle both. An empty array is the explicit statement that
replies come back on a temporary queue named by the request, which is a real and correct
configuration — not a mistake. The UI says so, so the operator can tell the two apart.

### D3 — A multi-address flow has no reply destination until a reply joins it

Today a shared-queue flow is stamped at creation with the expectation's single reply
address. With a set that stamp would be a guess, and a wrong one two times out of three.
Instead the flow is created with `reply_destination` null and the joining reply fills it in.
This costs nothing at join time — `findOpenMatches` already matches a shared-queue flow on
correlation identity alone — and makes the stored flow answer a question it could not answer
before: *which* reply queue served this exchange.

For a single-element set the old behaviour is kept — the destination is known in advance, so
stamping it at creation preserves the existing "awaiting a reply on this queue" reading in
the UI.

### D4 — Sample every serving node, not the first

`servingNode` becomes `servingNodes`, returning every node that is active, has no last
error, and has a Core URL. Each is browsed for the request address and for each reply
address, at the existing page size of 20 and the existing 5s cadence. The cost is linear in
node count and independent of queue depth, which keeps non-negotiable #1 intact: a scrape is
still a bounded, small read per node.

Dedupe already protects the correlator from seeing the same message twice —
`recentRequestFlow` plus `uq_rr_flow_request` — which matters now that a message can be
observed from more than one node while it is being replicated.

### D5 — Sampling failures are reported, once

Swallowing every sampling exception at `debug` is what let a broken configuration look like
an idle one. The sampler keeps a per-expectation failure counter and logs at `warn` on the
first failure and then at most once per minute, naming the expectation, the node and the
message. It still never throws: one unreachable node must not stop the others being sampled.

## Risks / Trade-offs

- **Breaking API change.** `replyAddress` → `replyAddresses` changes the expectation
  payloads. Pre-1.0 with a `dev` channel only, so the cost is a CHANGELOG `### Breaking`
  note rather than a migration path. Mitigated by both sides landing in one change.
- **More Core browses per tick.** Three nodes × (one request address + three reply
  addresses) is 12 browses per tick where there was 1. Each is a bounded page-1 read on the
  Core connection pool, outside the Jolokia rate-limiter budget by design. If this proves
  too chatty the cadence is already configurable; the page size is not, deliberately.
- **Array columns are less portable.** Postgres is the only supported database (ADR-0011),
  so this is not a live constraint.

## Migration

One changeset: add `reply_addresses TEXT[] NOT NULL DEFAULT '{}'`, backfill
`ARRAY[reply_address]` where the old column is not null, drop `reply_address`. Reversible
by the inverse; the released 007 and 011 changesets are untouched.
