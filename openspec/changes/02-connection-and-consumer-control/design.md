## Context

See `proposal.md` — Why. This section records only the constraints that shape the
approach.

What already exists and is reused unchanged:

```
GET  .../connections|sessions|consumers   PagedListService → BrokerListOps.fetch
                                          one op per node, paged, cached by tier
slow-consumer detection                   ADR-0044, two authorities
POST .../messages/actions/{action}        the dry-run → cap → audit chain
```

Constraints that follow:

- **`BrokerListOps.fetch` returns rows the broker paged**, from a per-node call.
  The connection id in a row is the id *that node* issued. There is no cluster-wide
  connection identity, and inventing one would be a lie with a nice API.
- **Rows are served from the tiered scrape cache.** By the time an operator clicks,
  the row can be stale. The design has to treat "gone" as normal, not exceptional.
- **`MessageService.Outcome` already models `DryRun(count, cap, overCap, node)`.**
  The address-scoped close is the same shape and can reuse it rather than adding a
  parallel vocabulary.
- **`ClusterAccessGuard.requireCluster` hides cluster existence** behind not-found.
  Connection ids must not become an oracle that leaks it back.

## Goals / Non-Goals

**Goals**

- Close the loop that slow-consumer detection opens, with the narrowest verb that
  does it.
- Be honest about ephemerality rather than papering over it with retries.
- One audit record that identifies the *application* that was disconnected, not
  just an id that no longer resolves.

**Non-Goals**

- No cluster-wide fan-out for a single close. See D1.
- No heuristic targeting ("close everything slow"). See D5.
- No reconnection, throttling, or consumer flow control. Different feature,
  different risk.
- No session-level enumeration beyond what the resource views already show.

## Decisions

### D1 — A single close names a node; only the address-scoped close is cluster-wide

Every other mutating operation in Studio takes a cluster. This one does not, and
the inconsistency is deliberate.

A connection id is issued by, and unique to, one node. A cluster-wide close by id
would either have to try the id on every node — where it is meaningless and may
collide with an unrelated connection — or resolve it through topology, which is
the same as naming the node with extra steps and a race in the middle.

The address-scoped close is different: an address exists on every node, and closing
its consumers is a coherent cluster-wide intent. It fans out and reports per node,
reusing the change-01 outcome shape if that change has landed, and its own if it
has not.

### D2 — A target that is already gone is a success

The requested state is "this connection is not open". If the broker reports no such
connection, that state holds. Returning an error would make the honest response to
a stale row look like a failure, and would push clients into retry loops against a
non-idempotent operation — the worst possible combination.

The result distinguishes `CLOSED` from `ALREADY_GONE` so the audit record is
accurate, but both are success.

### D3 — The confirmation and the audit record name the application, not the id

An operator cannot recognise `a3f1-...` as the right connection. They can recognise
a client id, a remote address, or a user. So:

- the typed confirmation is against the client id where the broker reports one, and
  the remote address otherwise;
- the audit event captures client id, remote address, user, session count and
  consumer count **as read immediately before the close**, because after the close
  none of it is resolvable.

This is why the close path re-reads the connection rather than trusting the row the
UI sent. The re-read is also the last chance to notice the id now refers to a
different connection.

### D4 — In-flight message consequences are stated up front

Closing a consumer returns its in-flight messages to the queue and increments their
redelivery count, which can push a message past its max-delivery-attempts and into
the DLQ. That is a real, sometimes irreversible consequence of a button labelled
"close connection".

The confirmation names it, with the count of in-flight messages where the broker
reports it. Learning about it later from a DLQ entry is not an acceptable way for
an operator to find out.

### D5 — No heuristic targeting

Rejected: a "close all slow consumers" action driven by the ADR-0044 detection.

Slow-consumer detection has false positives by construction — a consumer doing
legitimate slow work looks identical to a wedged one from the broker's side. An
action that selects its own targets from a heuristic turns every false positive
into a disconnected production application. The operator sees the detection, picks
the target, and confirms it by name.

### D6 — The address-scoped close is capped

It affects an unbounded number of consumers, so it takes a dry-run reporting the
per-node count and is evaluated against the ADR-0022 bulk cap with the same
`override` escape. Nothing new; the point is that it is not exempt because it is
"just a disconnect".

## Risks

- **Closing the wrong connection.** Mitigated by the pre-close re-read (D3), the
  confirmation against a human-recognisable identifier, and the audit snapshot.
  Not eliminated — an id can be reissued between the read and the close, and the
  spec says the operation is best-effort about identity.
- **A closed application reconnects immediately and nothing changes.** This is the
  expected outcome for a healthy client and the point of the feature for a wedged
  one. The UI should not present a close as a fix; it presents it as a disconnect.
- **DLQ amplification.** D4's disclosure, plus the fact that the DLQ view already
  exists to see the result.

## Open for refinement

D5's stance and D1's asymmetry are the two most likely to be argued with, and both
should be. Revise this file together with `tasks.md` and the spec deltas if they
move.
