## Context

See `proposal.md` — Why. The constraints that shape the approach:

**The broker offers no complete, non-destructive, zero-mutation tap.** A Core consumer is
complete and eats the operator's traffic. A browse is non-destructive and is a snapshot. A
divert is complete and non-destructive and mutates broker configuration. ADR-0060 chose the
browse and named the cost. This change chooses the divert and pays a different cost, which
the rest of this document is mostly about bounding.

**Verified on the real classpath (`artemis-core-client-2.56.0`), not from memory.**
`ActiveMQServerControl` carries `createDivert` / `destroyDivert` / `listDivertNames`,
`addAddressSettings(addressMatch, jsonSettings)` / `removeAddressSettings`, and single-String
JSON-configuration overloads of `createQueue` and `createDivert`. All are reachable over
Jolokia. Artemis's `copied-message-properties.adoc` lists **diverting** among the operations
that preserve `_AMQ_ORIG_ADDRESS`, `_AMQ_ORIG_QUEUE` and `_AMQ_ORIG_MESSAGE_ID`.

**What already exists and must be reused, not rebuilt.** `CorePool` and
`CoreSubscriptionManager` already run a Core consumer per node for `activemq.notifications`,
with backoff and a cached verdict. `MessageBrowser` / `CoreMessageTransport` already map an
Artemis message to the console's row shape. `NodeCallLimiter` already rate-limits every
management call. `queue_snapshot.messagesAdded` already feeds `SqlTailPoller`'s gap estimate.
`CapabilityLedger` already remembers management-write refusals. `message_index` is already
range-partitioned with a partition maintainer. None of these get a second implementation.

## Goals / Non-Goals

**Goals**

- One reading mechanism feeding the tail, the index and request-reply correlation.
- A tap whose worst case — Studio dead, forever — is a fixed-size buffer on the broker.
- Per-node truth: capture state, coverage windows and loss are per node, because failover
  and broker RBAC are per-node facts.
- Search over stored bodies that is worth the name, without a second datastore.

**Non-Goals**

- Capture is not enabled by default, is not inferred, and is not a consequence of any other
  action. ADR-0059 D1 is unchanged.
- Sampling is not removed. It remains the path for every uncaptured queue.
- Request-reply is not made complete. Temporary reply queues cannot be pre-diverted.
- No message ordering guarantee between the capture stream and the original. A non-exclusive
  divert copy is independent of the original's routing outcome.
- Bridges remain read-only, permanently, as change 04 decided.

## Decisions

### D1 — The tap is a non-exclusive divert into a ring-bounded, non-durable queue

`artemis-studio.capture.<instanceId>.<subscriptionId>` on each live node. Non-exclusive so
production routing is untouched. `ring-size` bounds the queue in size, `expiry-delay` with
`auto-create-expiry-resources=false` bounds it in age, and `address-full-policy=DROP` on the
capture address prefix bounds the address. Non-durable, so the queue itself is not written to
the journal and does not come back after a restart — but the divert and both settings do come
back (ADR-0065), so the tap as a whole is not self-cleaning and every removal is an explicit
act. What the divert then routes into on a restarted broker is a question L3 answers with a
measurement, not an assumption: it is the same class of claim ADR-0065 exists because of.

*Why not a Core consumer on the source queue:* complete and destructive — Studio would eat
the traffic it is displaying. *Why not the notifications stream:* it carries lifecycle
events, not bodies, so it cannot evaluate the query it is tailing. *Why not a broker plugin
or a divert transformer:* both require deploying a class onto the broker, which is exactly
the operator setup this change exists to avoid.

Artemis's own documentation warns against paging an address that has ring queues, which is
why the address setting is part of the tap and not an optional extra.

### D2 — Identity comes from Studio's own state first, the broker's headers second

A diverted copy is a different message: different address, new broker message ID. Relying on
`_AMQ_ORIG_*` alone would make correctness a function of the broker's version.

So: **one capture queue per captured source address**, and the mapping from capture queue to
source address is written by Studio when the tap is installed. The consumer therefore knows
the original address from *which queue it is draining* — no header required, no version
dependency. `_AMQ_ORIG_MESSAGE_ID` then supplies the source message ID on top, which is what
`VerifyOnBroker` and the replay path need. Where it is absent the source message ID is null,
and the verify control is offered-but-disabled with the reason stated — the existing
"unknown is not unavailable" pattern (ADR-0049 D5), never a hidden button.

### D3 — Capture is address-scoped, and the product says so rather than guessing

A divert copies at address routing, before multicast fan-out. For an address with several
bound queues, capture records one row and genuinely cannot say which queues received it.

The alternative — a divert per subscription queue — is not expressible for most topologies,
because subscription queues share one address, and where it is expressible it multiplies
broker objects and load. Rejected. The honest statement is cheaper and more useful than a
wrong number. Anycast is unaffected: address and queue coincide.

### D4 — Reconciliation is the only lifecycle mechanism

Desired state is the capture subscriptions in Postgres. Actual state is `listDivertNames`
filtered to this instance's prefix, per node. One idempotent loop converges them and is
simultaneously the crash-recovery path, the broker-restart path, the failover path, and the
new-queue path. There is no separate install flow to keep in step with it — an install is a
subscription row plus the next pass.

This is what makes failover survivable: a promoted backup carries no runtime divert, and the
next pass installs one. It is also why the gap must be recorded — the backup was live and
uncaptured for the interval between promotion and the next pass, and a coverage window that
did not record that would be a lie.

### D5 — Ownership is encoded in the object name, and concurrency is settled by a lock

The divert name carries a Studio instance id, and the reconciler destroys only diverts
carrying its own. That is what stops two *different* Studios pointed at one broker from
tearing down each other's taps — a failure mode with no other defence, since neither can
tell the other exists.

For two instances of the *same* Studio, name-based ownership is not enough: they share the
instance id and the database. Capture reconciliation therefore takes a **per-cluster Postgres
advisory lock**, pulling the roadmap's multi-instance HA item forward. Capture is the first
feature that cannot be correct without it, and a lock introduced later would have to be
retrofitted through this same code.

### D6 — Refuse rather than capture nothing, and refuse rather than capture unsafely

Two preflight checks, both of which produce a silent wrong answer if skipped:

- **An existing exclusive divert on the source address.** Artemis evaluates exclusive diverts
  before non-exclusive ones, so ours would never see the traffic. Capture would install
  cleanly and record nothing, which is the worst available outcome — indistinguishable from
  a quiet queue.
- **Authority to restrict the capture queue.** The capture queue is a complete second copy of
  production payload. Creating it without a `security-setting` restricting consume to
  Studio's own role would hand every broker-authorised client a tap on production traffic.
  Refuse, with the configuration that would grant it.

### D7 — Backpressure resolves toward the broker's safety every time

Ordering, from broker outward: the ring drops oldest; Studio's consumer applies a
per-subscription ingest rate cap so one firehose cannot starve the others; writes are batched
because a range-partitioned table with three GIN indexes will not absorb single-row inserts at
rate; oversized bodies are truncated at the consumer rather than transferred whole. Every drop
is estimated and reported per subscription, reusing `TailStatus` and the existing
`queue_snapshot.messagesAdded` delta rather than inventing a second gap metric.

The failure mode this ordering exists to prevent is "drop oldest, report the number"
degenerating into "drop everything, report a large number" — which is what happens if the
write path is left unbatched.

### D8 — Full-text is a functional GIN index and the `simple` configuration

`CREATE INDEX ... USING GIN (to_tsvector('simple', body))` — no generated column, no
backfill, no write-path change, and it cascades to partitions.

`simple`, not `english`: message bodies are identifiers, JSON and codes. English stemming
maps `orders`→`order` and `4471`→`4471` inconsistently across the corpus and would corrupt
exactly the lookups this feature exists for. `websearch_to_tsquery` then gives quoted
phrases, `-exclusion` and `or` for free, which is the part of a search engine operators
reach for.

Only text-ish bodies are indexed. `to_tsvector` over base64 produces garbage tokens and
bloats the index for no retrieval value.

Substring search is unchanged. The existing `gin_trgm_ops` index already serves `ILIKE` as
well as `LIKE` — verified against Postgres 17, both plan as a Bitmap Index Scan — so no second
trigram index is added.

*Why not Elasticsearch:* ADR-0059 rejected it and nothing here changes the reasoning — a
second system to deploy, secure and operate, against a product whose entire packaging story
is one container and a Postgres.

### D9 — Execution is POST-then-stream

Query text moves into a request body; the stream is opened by reference. `EventSource` can
only issue a GET, so today the operator's predicates — which contain the values they are
searching for — are written to every proxy access log on the path, and a long query hits a
URL length limit. The reference is short-lived and scoped to the caller.

### D10 — The console's honesty is preserved and its layout is fixed

Every statement the screen makes today is correct and stays. What changes is that they stop
displacing the result: one meta bar with a disclosure, and a results pane that owns the
viewport. A screen that pushes its rows below the fold to explain them is one an operator
scrolls past — losing exactly the text they needed.

The permanent, non-dismissable sampling notice survives ADR-0060's supersession as an
obligation for *sampled* tails. A captured tail makes a different claim, and making the same
claim for both would be the most damaging thing this change could do.

## Risks / Trade-offs

- **Studio now mutates broker routing on a schedule, not on a click.** A tap must be
  re-asserted after failover, so the reconciler acts unattended. → Ownership encoded in the
  object name; destroy restricted to this instance's own objects; every install and removal
  audited before the broker call; the reconciler acts on drift only and through
  `NodeCallLimiter`.
- **A second copy of production payload exists on the broker.** → A `security-setting`
  restricting it, installed as part of the tap, and capture refused where it cannot be.
- **Studio's database now holds complete payload, not a sample.** Volume and completeness
  change; kind does not (ADR-0059 D6). → Opt-in, per-queue, audited; a size bound as well as
  a time bound; an optional capture filter (`createDivert` takes a `filterString` at no cost)
  so an operator can narrow both load and exposure.
- **A large message would be transferred whole into a TEXT column.** A single 100 MB message
  is an incident. → Per-message body cap applied at the consumer, reusing the existing
  `BODY_TRUNCATED` disclosure rather than inventing a second one.
- **Capture could be defeated silently by broker configuration.** → The exclusive-divert
  preflight, and a degraded state that is reported rather than inferred from an empty result.
- **`_AMQ_ORIG_MESSAGE_ID` may be absent on some brokers or protocols.** → The address
  mapping does not depend on it; only `VerifyOnBroker` degrades, and it says so.
- **Two backends now claim different things about completeness.** Rendering a captured row
  and a sampled row identically would undo the entire point. → Provenance is per row, stated
  in text, and tested.
- **The reconciler could thrash across many nodes and subscriptions.** → Fingerprint the
  desired target set and act only on drift, the discipline `MessageIndexCapture` already
  applies to tails.

## Migration Plan

Layered so the product works end to end at every step, and never trades a working product for
unfinished code.

1. **L1 — bug fixes only.** Retention maintenance no longer destroys a disabled
   subscription's rows; the case-insensitive body index; the keyboard-reachable tail gate;
   the bounded tail view; capture start failures surfaced instead of swallowed. Ships alone
   and is worth shipping alone.
2. **L2 — routing read view.** `broker/routing/`, the cross-node divert and bridge views, the
   runtime-versus-configured flag. No capture yet; operators can see routing for the first time.
3. **L3 — capture, single node, anycast.** Advisory lock, tap, reconciler, consumer, writer.
   Demonstrably complete capture.
4. **L4 — capture on a real cluster.** Per-node state, failover reassertion, multicast
   scoping, loss accounting, size and rate bounds.
5. **L5 — full-text.** Index, dialect, catalogue mirror.
6. **L6 — console.** POST-then-stream, layout, meta bar, history, export, tail pause, capture
   management UI.

**Schema:** one new changeset, additive. New `message_capture_node`; new columns on
`message_index` and `message_index_subscription`; two new indexes. Existing `message_index`
rows are backfilled as `SAMPLED`, which is what they are. Rollback drops the changeset; the
index degrades to what it holds today.

**Rollback:** deleting every capture subscription removes every broker object the feature
created. A Studio rolled back to the previous version leaves runtime diverts that its brokers
discard at their next restart, and that stay ring-bounded until then — the same guarantee
that covers Studio crashing.

**ADRs:** 0062 (capture; supersedes ADR-0059 and ADR-0060 — mark both superseded with a link,
never edit their decisions), 0063 (Postgres full-text, `simple` configuration), 0064
(POST-then-stream), 0065 (management-created diverts, address settings and security settings
survive a broker restart — measured on 2.56.0, correcting 0062's third cleanup guarantee and
change 04's impermanence disclosure).

`openspec/changes/04-divert-and-bridge-management/` is deleted; its `routing-management`,
`authorization`, `cross-node-resource-views` and `mcp-server` deltas are absorbed here in
full, with one requirement added that 04 could not have had — a capture divert is identified
as Studio's own in the routing view and is not deletable from there, because deleting it
would appear to succeed and then be undone by the next reconciliation.
