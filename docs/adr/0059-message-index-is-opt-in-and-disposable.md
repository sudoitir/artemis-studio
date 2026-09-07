# ADR-0059: The message index is opt-in, retention-bounded and disposable

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: Artemis Studio maintainers

## Context

The SQL Console (ADR-0058) can query the live brokers, which answers "what is on this
queue right now". It cannot answer the question operators actually ask after an
incident: "what happened to the message that was here an hour ago". A consumed message
is gone from the broker, and no amount of querying the broker will bring it back.

Studio already stores broker-derived state, and CLAUDE.md is explicit about what that
means: `queue_snapshot` and `metric_sample` are a disposable cache, rebuilt from the
brokers, never the source of truth. An index of message bodies is different in one
respect that matters. It is not rebuildable — the brokers cannot re-supply a message
that has been consumed — and it holds application payload, which is a data-retention
concern rather than a caching one.

The temptation is to index everything by default, because that is what makes the
feature feel fast. That would make Studio a message store nobody asked for, holding
customer payload with no stated retention, discovered by whoever eventually reads the
database.

## Decision

We will keep a **message index**, and constrain it so that it stays a deliberate,
bounded, disposable act.

**D1 — Opt-in, per queue, never a side effect.** Nothing is indexed until an operator
creates a subscription naming the queues to capture. Running a query does not create
one. Browsing does not create one. Registering a cluster does not create one.
Creating, changing or deleting a subscription requires the settings write permission
and is audited, naming the queue pattern.

**D2 — It records what was observed, and says so.** The index is populated by the
same sampled poller that serves live tailing (ADR-0060), so it holds what was seen,
not everything that existed. No index-backed result claims completeness. Each row
carries when it was observed and when it was last still seen on its queue, so a
message observed once is distinguishable from one still present.

**D3 — Coverage gaps warn rather than under-report.** A subscription covers a queue
from the moment it began capturing until retention expires the oldest rows. A query
reaching outside that window, or naming a queue no subscription covers, says so. A
short result presented as the answer is the failure mode this exists to prevent.

**D4 — Retention is short by default and enforced by dropping ranges.** The table is
range-partitioned on the observation time and maintenance drops whole partitions,
never rows one at a time — the same shape as `metric_sample` and its partition
maintainer. The default retention is seven days: long enough for the incident that
motivated the subscription, short enough that an unattended subscription does not
accumulate payload indefinitely. Each subscription reports what it currently holds,
so the cost of having created one is visible to the person who created it.

**D5 — It is disposable and the operator can prove it.** Deleting a subscription
deletes everything it captured, in one action, stating the blast radius before it can
be armed. Losing the index degrades the console to live-broker queries; it does not
break it. This is the sense in which the CLAUDE.md rule still holds: the index is not
a source of truth for anything the product needs to function, and the product is
correct without it.

**D6 — Indexed payload is treated as retained data.** Querying the index needs the
same message read permission, scoped the same way, as querying a broker. The dialog
that creates a subscription states that message bodies will be stored by Studio for
the chosen period, before it can be confirmed.

## Consequences

- Studio's database now contains application payload. That is a change in kind, and
  it is why the feature is off by default and why the creation dialog says what it
  says. An operator who never opts in has a Studio that stores exactly what it stored
  before.
- The index is the only Studio-held state that cannot be rebuilt from the brokers.
  Backup and restore now have a case where "just re-scrape" is not the answer —
  though the honest answer remains that losing it costs history, not correctness.
- Two backends means every result has to carry its provenance, and the UI has to
  render "current" and "observed, possibly gone" differently. Rendering them
  identically would be the most damaging thing this feature could do.
- Retention defaulting to seven days will be too short for someone. Raising it is a
  per-subscription setting; making it unlimited is not offered, because an unbounded
  payload store is not a thing to arrive at by accident.

## Alternatives considered

- **Index every queue automatically.** The fast, obvious version, and it turns Studio
  into an unannounced message store holding customer data. Rejected on that alone.
- **No index; live queries only.** Simplest, and it cannot answer the question the
  feature exists for. A consumed message is invisible.
- **Index headers and properties but not the body.** Materially smaller retention
  concern, and it discards the field operators actually search — the order number is
  in the body, which is why the selector was insufficient in the first place.
- **Push the index into an external store (Elasticsearch, Loki).** A real answer at
  scale and a second system to deploy, secure and operate for a self-contained tool
  whose entire packaging story is one container and a Postgres. Revisit if per-queue
  volume ever makes Postgres the wrong shape.
