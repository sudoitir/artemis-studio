## Why

Request-reply tracing does not reconstruct a single flow against either of the clusters it
has been pointed at, because both answer on **more than one reply address, chosen by the
responder rather than by the operator**:

- One deployment names the reply queue after the **broker node** — three primaries, three
  reply queues, e.g. `orders.reply.broker-1`, `orders.reply.broker-2`, `orders.reply.broker-3`.
  Stable, so a fixed list would work.
- Another names it after the **client host** — `orders.reply.host-7` today, a different name
  as soon as another client connects, and gone again when that client goes away. No list
  survives a redeployment.

Three limitations combine to make that untraceable:

1. **An expectation holds exactly one reply address.** `rr_expectation.reply_address` is a
   single `TEXT` column, and the unique key `(cluster_id, request_address)` forbids
   declaring the same request address again to cover the rest. Faced with a field that
   plainly could not hold the answer, the operator left it empty — which is the worst
   outcome, because a shared-queue flow with no reply destination can never be joined and
   every flow ends orphaned.
2. **Even a list would not survive the production shape.** A per-client-host reply queue
   cannot be enumerated in advance; anything Studio is told today is stale the next time a
   client is redeployed. The name is not data an operator has — the *shape* is.
3. **Only one node is ever sampled.** `RrSampler.servingNode` takes the first active node
   with a Core URL and browses only that one. In a three-primary cluster the request and
   reply queues on the other two nodes are never read, so the correlation identity that
   only browsing can supply is missing for two thirds of the traffic.

The symptom is an empty Flows tab with no error anywhere: the sampler swallows its failures
at `log.debug`, so a correctly-configured-looking expectation produces nothing and says
nothing.

Depends on the request-reply design in the archived `2026-09-04-phase-5-request-reply-tracing`
change. It supersedes nothing: the correlation model, the six flow states and the two
observation channels are unchanged.

## What Changes

**An expectation declares reply addresses as a set of patterns.** `reply_address TEXT`
becomes `reply_addresses TEXT[]`. Each entry is either a literal address or a glob
containing `*`, so `orders.reply.*` covers every current and future per-node
or per-client reply queue without the expectation being edited. An empty set keeps its
existing meaning: replies arrive on a temporary queue named by the request's `replyTo`.

**Patterns resolve against addresses Studio already knows.** Expansion reads
`queue_snapshot`, which the scrape loop already fills, so a pattern costs no extra broker
call and picks up a new reply queue within one scrape cycle of it appearing. A pattern that
currently matches nothing is not an error — it is a queue that has not been created yet.

**A flow records the reply address that actually answered.** A flow whose expectation
resolves to more than one reply address is created with no reply destination, and takes it
from the joining reply. For a single resolved literal the current behaviour is kept — the
destination is known in advance, so it is stamped at creation.

**Shared-queue replies are observed, not only temp-queue ones.** `RrNotificationObserver`
currently forwards `MESSAGE_DELIVERED` only when it closes a temp-queue flow. A delivery on
a resolved reply address is now forwarded too, so a reply drained faster than the 5s sampler
tick is still seen.

**The sampler reads every serving node**, and each resolved reply address on each, at the
existing page size and cadence.

**Sampling failures stop being silent.** A failure is logged at `warn` naming the
expectation and the node, on first occurrence and at a bounded rate after.

**The UI states the requirement.** The reply-address field becomes a multi-value input that
accepts patterns, shows what each pattern currently resolves to, and explains that a request
with neither a `replyTo` nor a matching reply address cannot be joined.

## Impact

- Affected specs: `request-reply-tracing`.
- Affected schema: new changeset `017-rr-reply-addresses.sql` adding
  `rr_expectation.reply_addresses TEXT[] NOT NULL DEFAULT '{}'`, backfilling from
  `reply_address`, then dropping it. Released changesets 007 and 011 are untouched.
- Affected code: `RrExpectationEntity`, `RrExpectationRepository`, `RequestReplyService`,
  `RrViews`, `RrCorrelator`, `RrSampler`, `RrNotificationObserver`, and a new
  `ReplyAddressResolver`; `web/src/rr/ExpectationsView.tsx`.
- **Breaking API change**: `replyAddress` (string) → `replyAddresses` (array of string) on
  the expectation create, update and read payloads. Pre-1.0 on a `dev` channel only, so the
  cost is a `### Breaking` CHANGELOG block, not a compatibility shim.
- Does **not** fix production tracing on its own: that cluster's Core subscription is
  refused with `AMQ229099` because Studio authenticates as the account named in
  `<cluster-user>`. That is a broker-account change, recorded separately.
