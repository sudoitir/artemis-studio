## Context

Request-reply tracing was designed for the two textbook reply patterns: a temporary reply
queue named by the request's `replyTo`, and one shared reply queue named in the expectation.
Both clusters Studio has been pointed at use a third: **a shared reply queue per responder
instance**, where the name is chosen by whichever responder picked the request up.

The two deployments differ in a way that decides the design. Dev's three reply queues are
per broker node and stable, so a fixed list would work. Production's are per client host
(`nova.fcb.integration.reply.LIN00127`), created and destroyed as clients come and go — a
list is stale the moment a client is redeployed. What the operator reliably knows is not the
names but the **shape**, and the shape is stable in both.

## Goals / Non-Goals

- **Goals.** Trace an exchange whose replies are spread over reply queues the operator can
  describe but not enumerate; sample every node that can serve the traced addresses; make
  the reply-address requirement visible before an operator has waited for flows that can
  never appear.
- **Non-goals.** Per-reply-address deadlines or sample rates; regular expressions; changing
  the six flow states, the deadline resolution order, or the two observation channels.

## Decisions

### D1 — Glob patterns, not regular expressions

An entry is a literal address unless it contains `*`, which matches any run of characters
including `.`. `nova.fcb.integration.reply.*` is the whole vocabulary this needs.

Regular expressions were rejected: they invite a pattern that takes exponential time on a
crafted address, they are hard to show back to an operator (matching what, exactly?), and
Artemis operators already read `#` and `*` in `address-setting match=` — a glob is the
idiom of the surrounding system. `#` is deliberately *not* aliased to `*`, because in
Artemis they differ (`*` is one word, `#` is any number) and quietly redefining `*` to mean
Artemis's `#` would be worse than having one syntax that is plainly Studio's own. The UI
says so next to the field.

Matching is anchored at both ends: `reply.*` does not match `other.reply.x`.

### D2 — A Postgres array, not a child table

`reply_addresses TEXT[]` rather than an `rr_expectation_reply` child table. The set is
small, always read whole with its parent, never queried independently and never joined to. A
child table would add a query to the correlator's hot path — `expectationFor` already
re-reads a cluster's expectations per observation — to serve a read this feature never
performs. Hibernate maps `TEXT[]` to `List<String>`; `ddl-auto=validate` is satisfied by the
Liquibase-declared type.

Column placement follows the schema convention: a varlena goes with the other 4-byte-aligned
columns, before the uuid and boolean tail.

### D3 — An empty set means "temporary reply queue", not "unset"

`NOT NULL DEFAULT '{}'`. Nullable would give two spellings of one state and force every
reader to handle both. An empty array is the explicit statement that replies come back on a
temp queue named by the request — a real and correct configuration, not an omission. The UI
distinguishes the two so an operator can tell "I meant temp queues" from "I have not filled
this in", which is exactly the confusion that produced the untraceable expectation.

### D4 — Patterns resolve against `queue_snapshot`, not the broker

Expansion reads the `queue_snapshot` rows the scrape loop already writes
(`QueueSnapshotRepository.findByClusterId`), taking the distinct `address` values. This
keeps non-negotiable #1 intact: a pattern costs no broker call at all, and a reply queue
created by a new client is picked up within one scrape cycle.

The cost is that a reply queue is invisible to tracing until the first scrape after it
appears. That is the right trade: the alternative — resolving against the broker on every
observation — turns a per-message hot path into a per-message management call.

A new `ReplyAddressResolver` owns both halves so the sampler and the correlator cannot drift:
`resolve(clusterId, expectation)` returns the concrete addresses to browse, and
`matches(expectation, address)` answers whether an observed address belongs to it. Results
are cached per cluster for one scrape interval; the correlator calls `matches` per
notification, and re-scanning every snapshot row per event would be the same mistake
`expectationFor` already makes.

### D5 — A multi-address flow has no reply destination until a reply joins it

Today a shared-queue flow is stamped at creation with the expectation's single reply
address. Under a pattern that stamp would be a guess, and a wrong one most of the time.
Instead the flow is created with `reply_destination` null and the joining reply fills it in.
This costs nothing at join time — `RrFlowRepository.findOpenMatches` already matches a
shared-queue flow on correlation identity alone, never on destination — and it makes the
stored flow answer a question it could not answer before: *which* responder served this
exchange.

When resolution yields exactly one literal, the destination is known in advance and is
stamped at creation, preserving the existing "awaiting a reply on this queue" reading.

### D6 — `MESSAGE_DELIVERED` on a resolved reply address counts as a reply

`RrNotificationObserver` forwards `MESSAGE_DELIVERED` only when it closes a temp-queue flow
(`hasOpenTempQueueFlow`). For the shared-queue pattern the sampler is the only channel, and
it browses page 1 every 5s — a reply consumed in milliseconds is never in the queue when the
sampler looks. That is the second reason these flows time out.

So a delivery on an address that resolves for some expectation is forwarded as a
`ReplySeen`. The notification carries no correlation id, so the join falls to
`findOpenMatches`, which for a shared-queue flow needs one — meaning this observation
completes a flow only when the sampler has already supplied the correlation identity, and
otherwise records an orphaned reply. It is an improvement in coverage, not a replacement for
browsing, and the spec says so rather than implying notifications alone are sufficient.

### D7 — Sample every serving node

`servingNode` becomes `servingNodes`: every node that is active, has no last error, and has
a Core URL. Each is browsed for the request address and for each resolved reply address, at
the existing page size (20) and cadence (5s). Cost is linear in node count and independent of
queue depth.

Dedupe already covers a message seen from more than one node — `recentRequestFlow` plus
`uq_rr_flow_request` — which matters now that replication can surface the same message twice.

An unbounded pattern is a real risk here: `*` alone would resolve to every address on the
broker and multiply browses by the address count. Resolution is capped (32 addresses per
expectation), and the excess is reported in the capability text rather than silently
truncated.

## Risks / Trade-offs

- **Breaking API change.** `replyAddress` → `replyAddresses`. Both sides land together; the
  CHANGELOG carries a `### Breaking` block.
- **More Core browses per tick.** Three nodes × (one request address + three resolved reply
  addresses) is 12 bounded page-1 browses where there was 1. They run on the Core pool,
  outside the Jolokia rate-limiter budget by design. The cadence is configurable; the page
  size deliberately is not.
- **A pattern can be too broad.** Mitigated by the resolution cap and by showing the
  operator what a pattern currently resolves to, at the moment they type it.
- **Array columns are Postgres-specific.** Postgres is the only supported database
  (ADR-0011), so this is not a live constraint.

## Migration

One changeset: add `reply_addresses TEXT[] NOT NULL DEFAULT '{}'`, backfill
`ARRAY[reply_address]` where the old column is not null, drop `reply_address`. Reversible by
the inverse. No ADR is required — this is a data-model change inside an accepted design, and
the glob-versus-regex decision is recorded here.
