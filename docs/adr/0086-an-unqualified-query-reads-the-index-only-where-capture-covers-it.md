# ADR-0086: An unqualified query reads the index only where capture covers it

- **Status**: accepted
- **Date**: 2026-09-19
- **Deciders**: Artemis Studio maintainers
- **Extends**: [ADR-0058](0058-sql-console-query-model.md) D3 (the source qualifier). It
  records the source used when the qualifier is absent, which until now only the
  `sql-console` spec stated
- **Relates to**: [ADR-0059](0059-message-index-is-opt-in-and-disposable.md),
  [ADR-0060](0060-sampled-tail-is-not-a-capture.md),
  [ADR-0062](0062-message-capture-is-a-divert-into-a-ring-bounded-queue.md)

## Context

ADR-0058 D3 makes the source a schema qualifier: `FROM broker."Q"` or `FROM index."Q"`.
When the qualifier is absent, the `sql-console` spec says the console reads the index for
a queue that is indexed, and the live brokers otherwise. "Indexed" meant any enabled
subscription whose pattern matches the queue, whatever its mode.

A queue can be indexed in two ways, and they are not equally complete:

- **CAPTURE** (ADR-0062) diverts a copy of every message routed to the address into the
  index. For the nodes where capture is active, the index holds everything.
- **SAMPLE** (ADR-0060) polls the queue on an interval and records what it saw. A message
  that arrives and is consumed between two polls is never recorded, and until the first
  poll the index holds nothing at all.

Measured on the dev stack: a queue held 3 messages, a SAMPLE subscription was created for
it, and `SELECT * FROM "repro.c7"` returned 0 rows, read from the empty index, until the
first poll a few seconds later. An operator who types the plain query to see what is on a
queue gets a lossy copy of it. For a queue that consumers drain quickly, that copy can
hold almost nothing.

## Decision

We will read the index for an unqualified `FROM` only when every target is covered by an
enabled **CAPTURE** subscription. A queue covered only by a SAMPLE subscription is read
from the live brokers.

`FROM index."Q"` still reads the index for any subscription, SAMPLE included. What a
sampled index holds is still available, but only when the operator asks for it by name.

The notices on a captured result, `CAPTURE_NODE_GAP` for nodes where capture is not
active and the coverage gaps, are unchanged.

## Consequences

- The plain query on a sampled queue answers what is on the queue now, not what a poll
  happened to see. A message consumed since it was sampled no longer appears unless the
  operator writes `FROM index`.
- A plain query on a sampled queue now costs broker reads, and the cost ceiling applies
  to it as to any broker query. Before, the index answered it at no broker cost.
- The `sql-console` spec's "choose the index for a queue that is indexed" now means
  "captured". The spec text has not been updated yet, because this session made no
  OpenSpec change. It has to be updated the next time that capability is touched.

## Alternatives considered

- **Keep the index for SAMPLE and add a notice.** The notice would sit on a result that
  is already wrong for the question most operators ask. A short list with a caveat still
  reads as the answer.
- **Read both and merge.** The two sources answer different questions, "what is on the
  queue" and "what passed through it". A merge answers neither, and it doubles the cost.
