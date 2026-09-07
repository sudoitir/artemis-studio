---
title: SQL Console
description: Query the messages across an Artemis cluster in SQL — including the body, which a JMS selector cannot see — with the cost of the query shown before it runs.
---

# SQL Console

![Querying every queue in a cluster from the SQL Console, then following the live tail](/img/sql-console.gif)

The question an operator actually arrives with is *"where did order 4471 go?"*.
Artemis cannot answer it. Its only server-side filter is a JMS selector, which
sees message headers and application properties — **not the body** — and applies
to one queue on one node at a time.

The SQL Console answers it, across every queue in the cluster, in one query.

```sql
SELECT * FROM "ORDER.*"
WHERE body->>'orderId' = '4471'
LIMIT 50
```

## A real dialect, a small subset

`SELECT` only. One `FROM`. `WHERE`, `ORDER BY`, `LIMIT`. No join, no subquery, no
union, no CTE — and no mutation is expressible at all.

It is real SQL syntax on purpose, so the editor's off-the-shelf grammar,
highlighting and completion work unmodified. Queries are parsed to an AST and
validated against a fixed column catalogue, node type by node type, rejecting by
default. Nothing is validated by pattern-matching the query text; a regular
expression asserting the absence of `DROP` is how this is usually got wrong.

Queue names are always double-quoted — `ORDER.IN` is a reserved word and a dot —
and wildcards are Artemis', not SQL's: `*` matches one dot-delimited level, `#`
matches many.

## Where the query reads

The schema qualifier picks the backend, in the query text itself, so a query
pasted into a ticket still says where it read from.

| | |
|---|---|
| `FROM broker."Q"` | The live brokers. Current truth. |
| `FROM index."Q"` | The historical index — still holds messages that have since been consumed, and only covers queues you subscribed. |
| `FROM "Q"` | The planner chooses, and the plan says which it picked. |

## What a predicate costs — before you run it

This is the part worth knowing. Every top-level `AND` conjunct is classified:

| Class | Meaning |
|---|---|
| **Target** | Chooses which queues are read. Costs nothing. |
| **Pushdown** | Becomes a JMS selector, so the broker filters and Studio never sees the non-matches. Costs nothing. |
| **Scan** | Studio examines every message the broker returned. |

Headers and application properties push down. The body never can — nothing but
Studio can read it. The plan strip above the editor tells you which your query
is **before it runs**, and over the configured cost ceiling the query is refused
with the estimate, the ceiling and a hint for narrowing it. It is refused rather
than truncated: a truncated result is indistinguishable from a complete one at a
glance, and an operator mid-incident reads it as "not there".

::: tip One trap worth stating
A predicate that is free on its own stops being free inside an `OR` with a body
predicate. Pushing down one side of an `OR` would narrow the set the other side
gets to see — so a disjunction containing a scan is scanned as a whole. This is
the one mistake in this area that produces a silently *wrong* answer rather than
a slow one.
:::

## Columns

Selector-eligible (free): `priority`, `durable`, `timestamp`, `expiration`,
`size`, `jmsType`, `correlationId`, `groupId`, `userId`, and any application
property as `props.<name>`.

Target (free): `queue`, `address`, `node`.

Scan: `body`, `messageId`, `messageType`, `replyTo`, and — on the index only —
`observedAt` and `lastSeenAt`.

A JSON field inside the body is `body->>'orderId'`. The only functions the
dialect accepts are `now()`, `lower()`, `upper()`, plus `interval` in a relative
time. Relative time is normalised through each node's measured clock offset, so a
broker with a skewed clock still answers correctly.

## Worked examples

```sql
-- Cheapest possible: no predicate, the broker returns its head pages.
SELECT * FROM "ORDER.IN" ORDER BY timestamp DESC LIMIT 100;

-- Both predicates become a selector across a wildcard. Still free.
SELECT * FROM "ORDER.*" WHERE priority > 4 AND durable = true LIMIT 200;

-- Application properties are selector-eligible too.
SELECT * FROM "ORDER.IN" WHERE props.tenant = 'acme' LIMIT 200;

-- A scan. Narrow it with a header predicate first.
SELECT * FROM "ORDER.IN" WHERE body LIKE '%4471%' LIMIT 50;

-- A time window, quantized and skew-corrected.
SELECT * FROM "ORDER.*"
WHERE timestamp > now() - interval '2 hours'
ORDER BY timestamp DESC LIMIT 500;
```

## The optional index

The message index is **opt-in per queue**, retention-bounded, and disposable. It
exists so a question can be asked about a message that has already been consumed
— which the brokers, correctly, no longer know anything about. It is treated as
retained payload for the purposes of access control and deletion, and dropping
it costs history, never truth.

## The live tail

The tail is a **poll**, never a consume. It cannot mutate anything and it does
not compete with your consumers. It says so permanently in the UI, because a
sampled tail that reads as a complete capture is a way to conclude a message was
never sent.

## Acting on a result

You cannot. Deliberately. Acting on a result row routes back through the ordinary
message operations, which already carry the dry run, the bulk cap and the audit
record — a second path to a destructive verb would be a second place to get the
safety contract wrong.

## Decisions

- [ADR-0058](/reference/adr/0058-sql-console-query-model) — the dialect, AST validation, the predicate split
- [ADR-0059](/reference/adr/0059-message-index-is-opt-in-and-disposable) — the index
- [ADR-0060](/reference/adr/0060-sampled-tail-is-not-a-capture) — the tail
