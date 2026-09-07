# ADR-0057: Connection identifiers are node-local and ephemeral

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: Artemis Studio maintainers

## Context

Studio detects slow consumers (ADR-0044) and lists every connection, session and
consumer across a cluster (ADR-0017). It then stops: the operator who has just
been told which consumer is holding up a queue has to leave the product to do the
one thing the finding implies. Closing that connection returns its in-flight
messages to the queue and lets a healthy instance take them — the most common 3am
intervention on an Artemis cluster, and the reason people keep a JMX console open
next to Studio.

Adding the verb runs into a property nothing else in Studio has to deal with.
Every other mutating operation names a **cluster** and fans out to its live nodes
(ADR-0049): a queue name means the same thing everywhere. A connection identifier
does not. It is issued by, and unique to, the node that accepted the TCP
connection, and it is gone the moment that connection drops — which may be
between the read that listed it and the close that targets it, because the rows
an operator clicks come from a tiered cache.

The relevant management surface, taken from `ActiveMQServerControl` in
`artemis-core-client 2.56.0` rather than from memory:

```
boolean closeConnectionWithID(String connectionID)
boolean closeSessionWithID(String connectionID, String sessionID)
boolean closeConsumerConnectionsForAddress(String address)
```

All three answer `false` for a target the node does not have. None raises, and
there is no `AMQ…` code to classify — so "already gone" arrives as an ordinary
successful response with a `false` value, not as an error.

## Decision

We will treat a connection identifier as **node-local and ephemeral**, and let
that shape the whole feature.

**D1 — a close by id names a node; only the address-scoped close is cluster-wide.**
`POST /clusters/{c}/nodes/{n}/connections/{id}/close` and its session and consumer
siblings take a node. This is the one mutating route in Studio that is not a
cluster-wide fan-out, and the inconsistency is deliberate: trying an identifier on
every node would either mean nothing there or collide with an unrelated
connection. Closing every consumer bound to an **address** is a coherent
cluster-wide intent, so that one names the cluster, fans out, and reports per
node.

**D2 — a vanished target is a success.** The requested state — that connection is
not open — holds. Returning an error would make the honest response to a stale row
look like a failure and would push clients into retry loops against a
non-idempotent operation, which is the worst possible combination. The outcome
still distinguishes `APPLIED` from `ALREADY` so the audit record is accurate, and
the API answers a dedicated `alreadyGone` flag so no client has to infer it.

**D3 — the confirmation and the audit record name the application, not the id.**
The target is re-read immediately before the close, never trusted from the row a
client sent. That read is the only chance to capture who is being disconnected:
afterwards the identifier resolves to nothing. The typed confirmation is against
the client id the broker reports — or the remote address when it reports none —
because an operator cannot verify that `a3f1c9de` is the right connection. The
audit row carries client id, remote address, user, and session and consumer counts
as read a moment before.

**D4 — the in-flight consequence is stated before the confirmation can be armed.**
Closing a consumer returns the messages it holds to their queue and increments
their delivery count, which can push a message past its max-delivery-attempts and
into the DLQ. The confirmation says so, with the count where the broker reports it
(`messagesInTransit` on the consumer rows) and an explicit "not reported" where it
does not. Discovering this afterwards from a DLQ entry is not acceptable.

**D5 — nothing is retried, and nothing selects its own targets.** A retry may act
on a different connection that has since been issued the same identifier, so a
failed close is reported and left. And there is no "close every slow consumer"
action: slow-consumer detection has false positives by construction, and an
operation that picks its own targets from a heuristic turns every false positive
into a disconnected production application. Detection informs; the operator names
the target.

**D6 — the address-scoped close is capped.** It affects an unbounded number of
connections, so it previews per node and is evaluated against the ADR-0022 bulk
cap with the same `override` escape. It is not exempt because nothing is deleted:
every consumer it closes hands its in-flight messages back to a queue.

**D7 — one permission, `connection:close`, implied by nothing.** Authority over a
cluster's messages says nothing about authority to disconnect the applications
producing and consuming them.

The result type is the ADR-0049 `LifecycleOutcome` — with a single entry for a
node-scoped close — so there is one per-node outcome vocabulary and one UI
component across every cluster mutation.

## Consequences

- Studio can disconnect a running application. That is a heavier authority than
  anything it previously held, which is why the permission is separate and the
  audit row names the client rather than the identifier.
- The API gains a shape no other route has: one that takes a node id. Anyone
  reading the routes will notice; this ADR is the answer.
- Closing the wrong connection is possible and not fully preventable. An
  identifier can be reissued between the re-read and the close. The re-read, the
  human-recognisable confirmation and the audit snapshot narrow it; the spec is
  explicit that the operation is best-effort about identity.
- A close is a disconnect, not a fix. A healthy client reconnects immediately,
  which is the expected outcome; the UI presents it as a disconnect and never as
  a repair.
- The pre-close read costs up to three batched Jolokia POSTs for a consumer-rooted
  close (consumer → session → connection, then the connection's sessions and their
  consumers). Each is a broker-side filtered lookup rather than a full listing, and
  they happen only on a deliberate, one-at-a-time operator action.

## Alternatives considered

- **Resolve the node from topology and keep the cluster-scoped route shape.** Same
  work as naming the node, with a race in the middle and a route that lies about
  what it addresses.
- **Try the id on every node.** On every node but one it either matches nothing or
  matches an unrelated connection. The failure mode is closing the wrong
  application, silently.
- **Treat an already-gone target as 404.** Makes the single most common real-world
  outcome — a stale row — look like an error, and invites retries the operation
  cannot survive.
- **Confirm against the connection id.** It is what the row shows and what the API
  takes, so it is tempting. It is also unverifiable by a human, which makes the
  confirmation ceremony rather than a check.
- **A "close all slow consumers" action.** Rejected under D5. It is the feature
  people ask for and the one that turns a monitoring false positive into an
  outage.
