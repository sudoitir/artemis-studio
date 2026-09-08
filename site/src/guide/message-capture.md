---
title: Message capture
description: Capture every message an address routes into a Studio-owned, ring-bounded queue — what it creates on the broker, what it costs, and how to remove it.
---

# Message capture

The live tail and the message index both answer a weaker question than they look
like they answer. Both **poll**: they browse a queue every few seconds and record
what was sitting on it at that instant. A queue that drains faster than the
interval is invisible — which is most healthy queues, most of the time.

Capture closes that gap. It is opt-in, per queue, and it changes routing on your
brokers, so this page states exactly what it creates and exactly how it goes away.

## What it is

A **non-exclusive divert** copies every message an address routes into a queue
Studio owns and drains. Your consumers are untouched: a non-exclusive divert
copies, it does not take. Studio consumes only from its own copy.

```
ORDER.IN ──non-exclusive divert──► artemis-studio.capture.<instance>.ORDER.IN.<subscription>
   (per node)                          ring-size N · address-full-policy=DROP
                                       expiry-delay · non-durable
                                              │
                                       Core consumer
                                              │
                             live tail · message index · request-reply
```

## What it creates on each live node

| Object | Why |
|---|---|
| `divert` from the source address, **non-exclusive** | The copy. Production routing is unchanged. |
| A **non-durable, ring-bounded queue** | Holds the copies. `ring-size` means the broker drops the oldest rather than growing. |
| `address-setting` on `artemis-studio.capture.#` | `address-full-policy=DROP` so the broker never blocks or pages on Studio's account, plus an `expiry-delay` so an abandoned tap is bounded in age as well as in size. Artemis' own documentation warns against paging an address holding ring queues, which is why this is part of the tap and not optional. |
| `security-setting` on `artemis-studio.capture.#` | Restricts consuming that queue to Studio's own broker role. The capture queue is a complete second copy of production payload; creating it unguarded would hand a tap on your traffic to every client the broker authorises. |

The role Studio's connection holds is configuration — `artemis-studio.capture.broker-role`,
default `amq`. Studio cannot discover it; the broker exposes no "who am I" read.

## It does not disappear on its own

Diverts, address settings and security settings created over the management API
**survive a broker restart**. That was measured on Artemis 2.56.0, not assumed —
see [ADR-0065](/reference/adr/0065-runtime-broker-configuration-persists-across-restarts).

So removal is always an explicit act:

- **Delete the subscription** in Studio. It removes the divert, the queue, the
  address setting and the security setting from every node they were installed on.
- If Studio itself is gone, the ring and the expiry delay bound the cost: the
  queue holds at most `ring-size` messages, none older than the expiry delay, on
  a queue that is not written to the journal. It is a fixed, bounded cost — but it
  is yours to remove.

## What it will refuse to do

Two preflight checks, both because the alternative fails silently:

- **An exclusive divert already on the source address.** Artemis applies exclusive
  diverts before every other one, so a capture divert behind one would never see a
  message — it would install cleanly and record nothing, which is
  indistinguishable from a quiet queue. Capture is refused, naming the divert and
  the address its traffic actually goes to.
- **No authority to restrict the capture queue.** Refused, with the
  `security-setting` that would grant it.

## What a captured result claims

Capture is **address-scoped**. A divert copies at address routing, before
multicast fan-out, so for an address with several bound queues the index holds one
row per routed message and genuinely cannot say which subscription received it.
The console states that on any result where it applies. Anycast is unaffected —
address and queue coincide.

Capture is also **per node**. A tap is a node-local object, and each live node has
its own divert, its own capture queue and its own consumer. In a replicated pair the
divert lives in the bindings journal, so a promoted backup usually comes up with it
already there — measured, not assumed — and where it does not, the next reconcile
pass installs it. Either way the gap is recorded rather than averaged away: the
subscription reports state per node, and a query reading from a node that is not
capturing says so.

Captured rows are queryable by their **original** queue name — the mapping from
capture queue to source address is Studio's own state, not a broker header.
`_AMQ_ORIG_MESSAGE_ID` supplies the source message id on top; where the broker did
not copy it, "verify on broker" is offered and disabled with the reason, never
hidden.

## What bounds it

| Bound | Default | What happens at the limit |
|---|---|---|
| `ring-size` | 10,000 | The broker drops the oldest. The drop is estimated and reported per node. |
| `expiry-delay` | 24h | The broker expires it. `auto-create-expiry-resources` is off, so nothing accumulates. |
| Ingest rate | 500/s per subscription | Over-rate messages are dropped and counted, so one firehose cannot starve the others. |
| Body size | 256 KiB per message | Stored truncated and flagged, never transferred whole into Postgres. |
| Retention | 7 days | Partitions are dropped. |
| Stored size | 5 GiB per subscription | The subscription degrades and says so. |

An optional **capture filter** — an Artemis filter expression — narrows what the
broker copies and what Studio stores, at no cost to either.

## Permissions

Turning capture on and off needs `capture:write`. It is deliberately not
`settings:write`: capture mutates broker routing on a schedule and creates a second
copy of production payload, which is a different authority from changing how often
Studio polls. Every install and removal is audited before the broker call and
updated with the outcome.

## Decisions

- [ADR-0062](/reference/adr/0062-message-capture-is-a-divert-into-a-ring-bounded-queue) — the mechanism
- [ADR-0066](/reference/adr/0066-a-capture-tap-must-never-block-a-producer) — why the capture address auto-creates
- [ADR-0065](/reference/adr/0065-runtime-broker-configuration-persists-across-restarts) — why removal is explicit
- [ADR-0059](/reference/adr/0059-message-index-is-opt-in-and-disposable) — the index capture writes into
