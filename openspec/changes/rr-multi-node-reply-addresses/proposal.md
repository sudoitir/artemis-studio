## Why

Request-reply tracing does not produce a single flow against a real deployment whose
responders reply to a **per-node** shared reply queue. On the dev cluster
`nova-dev-server-20`, the traced request address `nova.fcb.integration.request.v1` is
answered on three distinct reply addresses — `nova.fcb.integration.reply.nova-10.100.7.20`,
`…-.21` and `…-.22`, one per application instance. Two limitations combine to make that
untraceable:

1. **An expectation holds exactly one reply address.** `rr_expectation.reply_address` is a
   single `TEXT` column. An operator with three reply queues can name one of them, and the
   unique key `(cluster_id, request_address)` forbids declaring the same request address
   three times to cover the rest. Naming none — which is what the operator did, because the
   form gives no reason to think it matters — means every shared-queue flow is created with
   a null reply destination and can never be joined by a reply.
2. **Only one node is ever sampled.** `RrSampler.servingNode` takes the *first* active node
   with a Core URL and browses only that one. In a three-primary cluster the request and
   reply queues of the other two nodes are never read, so the correlation identity that only
   browsing can supply (design.md D2) is missing for two thirds of the traffic.

The observable symptom is an empty Flows tab with no error anywhere: the sampler swallows
its failures at `log.debug`, and a correctly-configured-looking expectation produces
nothing. This change closes both gaps and makes the reply-address requirement legible in
the UI instead of implicit.

Depends on the request-reply design recorded in the archived
`2026-09-04-phase-5-request-reply-tracing` change. It does not supersede any ADR: the
correlation model, the six flow states and the two observation channels are unchanged.

## What Changes

**Expectations carry a set of reply addresses.** `reply_address TEXT` becomes
`reply_addresses TEXT[]` in a new changeset (released changesets 007 and 011 are never
edited). A single existing value is migrated into a one-element array; an empty array means
"replies arrive on a temporary queue named by the request's `replyTo`". The API accepts and
returns a list; `ExpectationView.replyAddress` becomes `replyAddresses`.

**Reply joining spans the set.** `RrCorrelator.onReplySeen` already matches a shared-queue
reply on correlation identity rather than on destination, so the join itself needs no
change — but a flow created from a request with no `replyTo` currently stamps the single
configured reply address as its `reply_destination`, which is now ambiguous. A flow whose
expectation names more than one reply address SHALL be created with no reply destination and
SHALL take the destination from the reply that joins it, so the recorded flow says which
queue actually answered.

**The sampler reads every serving node.** `RrSampler` samples each active, error-free node
that has a Core URL, not the first one, and browses each of the expectation's reply
addresses. Page size and cadence are unchanged, so the per-node cost is unchanged and the
work scales with node count rather than with queue depth.

**Sampling failures stop being silent.** A repeated sampling failure for an expectation is
logged at `warn` with the address and the node, once per expectation per backoff window,
rather than only at `debug`.

**The UI states the requirement.** The reply-address field becomes a multi-value input, and
the Expectations tab explains that a request without a `replyTo` and without any declared
reply address cannot be joined, so its flows will all end orphaned.

## Impact

- Affected specs: `request-reply-tracing`.
- Affected schema: new Liquibase changeset adding `rr_expectation.reply_addresses`; the
  released `reply_address` column is dropped in the same changeset after backfill, since
  nothing outside this feature reads it.
- Affected code: `RrExpectationEntity`, `RrExpectationRepository`, `RequestReplyService`,
  `RrViews`, `RrCorrelator`, `RrSampler`, `RrNotificationObserver`, `web/src/rr/ExpectationsView.tsx`.
- API change: `replyAddress` (string) → `replyAddresses` (array of string) on the
  expectation create, update and read payloads. Pre-1.0, no compatibility shim; the
  CHANGELOG carries a `### Breaking` note.
