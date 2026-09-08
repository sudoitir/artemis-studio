# ADR-0062: Message capture is a divert into a ring-bounded Studio queue

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: Artemis Studio maintainers

## Context

Studio has three answers to "what messages went through here", and they are three copies of
the same mechanism. `SqlTailPoller` re-browses a queue against a high-water mark.
`MessageIndexCapture` is that poller with the database as its sink (ADR-0059 D2, ADR-0060 D4).
`RrSampler` browses page 1 of every request-reply address. A browse reports what was still
sitting on a queue at the instant it ran, so a queue that drains faster than the poll interval
is invisible to all three.

The observable consequence is that the message index produces rows for `ExpiryQueue` and
almost nothing else, because messages *sit* on an expiry queue and do not sit on a healthy
`ORDER.IN`. An operator who opts a busy queue into indexing, waits, and finds it empty
concludes the feature is broken. It is not broken. It is sampling, exactly as ADR-0060 says
it is — and the honesty ADR-0060 requires does not make the product answer the question.

ADR-0060 called divert-based capture "the only complete answer" and deferred it over one
objection, quoted here in full because everything below is a response to it: a divert
"mutates broker configuration with a cleanup obligation that survives Studio crashing". If
Studio died between creating the divert and removing it, the operator would be left with a
broker quietly copying production traffic into a queue nobody is draining.

One fact, not available when ADR-0060 was written, reduces that objection to a bounded cost.

**A ring queue bounds the cost even when nothing drains it.** `ring-size` on the capture queue
plus `address-full-policy=DROP` on the capture address means the queue overwrites its oldest
entries. The broker never blocks a producer, never pages, never grows. The worst case for a
Studio that dies and never returns is a fixed-size buffer.

A second fact was assumed here in an earlier draft and turned out to be false. This ADR first
claimed that a divert created over the management API does not survive a broker restart, on
change 04's authority. It does survive, along with the address setting and the security
setting — measured against Artemis 2.56.0 and recorded in
[ADR-0065](0065-runtime-broker-configuration-persists-across-restarts.md). Nothing removes an
abandoned tap for us.

So the cleanup obligation is bounded two ways, not three, by who is still alive: Studio running
reclaims the orphan; Studio dead leaves a ring bounded in size by `ring-size` and in age by
`expiry-delay` (ADR-0065 D3). A broker restart changes nothing, which is why removal is always
an explicit act.

## Decision

We will capture messages with a **non-exclusive divert into a ring-bounded, Studio-owned
queue, drained by a Core consumer**, and we will keep sampling for everything not captured.

**D1 — Opt-in per queue, never a side effect, and never the default.** ADR-0059 D1 is
unchanged and now matters more, because capture stores everything rather than a sample.
Capture requires its own permission — `capture:write`, not the settings permission that gates
index subscriptions — because it mutates broker routing and begins retaining application
payload. Both facts are stated in the interface before the action can be armed.

**D2 — One tap, three consumers.** The capture stream feeds the SQL Console's live tail, the
message index, and request-reply correlation. This generalises ADR-0060 D4 rather than adding
a fourth reading mechanism, for the same reason D4 gave: mechanisms with slightly different
semantics eventually disagree about what "seen" means.

**D3 — Sampling stays.** A queue with no capture subscription is still tailed and still
indexed by browse. Capture raises the ceiling; removing the floor would make every uncaptured
queue worse than it is today in order to make captured ones better.

**D4 — Identity comes from Studio's own state first, the broker's headers second.** A diverted
copy is a different message: different address, new broker message ID. We create **one capture
queue per captured source address**, so the consumer knows the original address from which
queue it is draining — a fact Studio wrote down, not one the broker has to tell us. Artemis
does also preserve `_AMQ_ORIG_MESSAGE_ID` on a divert, and that supplies the source message id
on top, which is what verify-on-broker and replay need. Where it is absent the source id is
null and the verify control is offered-but-disabled with the reason stated (ADR-0049 D5).

Making address resolution depend on a broker header would make correctness a function of the
broker's version. Making it depend on our own installation record does not.

**D5 — Capture is address-scoped, and the product says so.** A divert copies at address
routing, before multicast fan-out. For an address with several bound queues, capture records
one row and cannot say which queues received it. We state that rather than guess. A divert per
subscription queue is not expressible for most topologies — subscription queues share one
address — and where it is expressible it multiplies broker objects for a number we would still
be inferring.

**D6 — Reconciliation is the only lifecycle mechanism.** Desired state is the subscriptions in
Postgres; actual state is `listDivertNames` per node, filtered to this instance's objects. One
idempotent loop converges them, and it is simultaneously the crash-recovery path, the
failover path and the new-queue path. There is no separate install flow that could drift out
of step with it.

Because a tap outlives the broker process (ADR-0065), the orphan sweep in that loop is the only
thing that ever removes one, and deleting a subscription must say so rather than implying the
broker will tidy up.

This is what makes failover survivable. A promoted backup carries no tap of its own; the next
pass installs one. It is also why the coverage window is recorded **per node** — the backup was
live and uncaptured between promotion and that pass, and a coverage record that omitted the
interval would be a lie of exactly the kind this ADR exists to stop telling.

**D7 — Ownership is in the object name; concurrency is settled by a lock.** The divert name
carries a Studio instance id, and the reconciler destroys only objects carrying its own. That
is the only available defence against two *different* Studios pointed at one broker tearing
down each other's taps, since neither can detect the other. For two instances of the *same*
Studio, sharing an id and a database, capture reconciliation takes a per-cluster Postgres
advisory lock.

**D8 — Refuse rather than capture nothing; refuse rather than capture unsafely.** Two
preflight checks, both of which otherwise produce a silent wrong answer:

- *An exclusive divert already on the source address.* Artemis evaluates exclusive diverts
  before non-exclusive ones, so ours would never see the traffic. Capture would install
  cleanly and record nothing — indistinguishable from a quiet queue, and therefore worse than
  a refusal.
- *No authority to restrict the capture queue.* The capture queue is a complete second copy of
  production payload. Creating it without a `security-setting` restricting consume to Studio's
  own role would hand a tap on production traffic to every client the broker authorises.

**D9 — Backpressure resolves toward the broker every time.** The ring drops oldest; a
per-subscription ingest rate cap stops one firehose starving the others; writes are batched,
because a range-partitioned table with three GIN indexes will not absorb single-row inserts at
rate; oversized bodies are truncated at the consumer rather than transferred whole. Every drop
is estimated and reported per subscription. Without the batching, "drop oldest and report the
number" degenerates into "drop everything and report a large number".

**D10 — A captured row claims what it can and nothing more.** It records that a message was
routed to an address. It is not evidence of delivery to any queue bound to that address, and
it is not evidence about whether the message is still anywhere. Captured and sampled rows make
different claims and are never rendered identically.

## Consequences

- **Studio now mutates broker routing, unattended and on a schedule.** Every previous broker
  write was an operator's click on a message or a queue. A tap must be re-asserted after
  failover, so the reconciler acts on its own. The blast radius of that loop is the thing this
  design most has to get right, which is why ownership is encoded in the name, destruction is
  restricted to our own objects, and every install and removal is audited before the call.
- **Studio's database now holds a complete copy of captured traffic.** ADR-0059 D6 already
  treats indexed payload as retained data; capture changes the volume and the completeness,
  not the kind. It is why retention grows a size bound alongside its time bound, and why the
  divert's `filterString` is exposed — narrowing capture narrows both load and exposure, at no
  cost, because the broker was going to evaluate a filter anyway.
- **The index can now claim completeness, for the first time, and only sometimes.** ADR-0059 D2
  forbade the claim outright because sampling was the only source. It is now true for a
  captured window on a captured node and false everywhere else, which is a harder thing to
  render than a blanket disclaimer. Rendering it wrong is worse than the disclaimer was.
- **Request-reply is improved and not completed.** Temporary reply queues cannot be
  pre-diverted, because a tap must exist on an address before a message is routed to it and
  such an address does not exist until the client creates it. That half stays
  notification-derived, and the interface says so rather than reporting an expectation as
  fully captured when part of its flow is not.
- **Two more things exist on the broker that Studio must own for their whole life** — an
  address setting and a security setting, both as durable as the divert (ADR-0065). They are
  part of the tap, not optional extras: Artemis's own documentation warns against paging an
  address that has ring queues, and the same address setting carries the `expiry-delay` that
  bounds an abandoned tap in age.
- ADR-0059 and ADR-0060 are superseded. ADR-0060's obligation — that a sampled tail states its
  sampling permanently and undismissably — survives its supersession and applies unchanged to
  every uncaptured tail. What is retired is the conclusion that sampling was the only thing
  available.

## Alternatives considered

- **A Core consumer on the source queue.** Complete, and destructive: Studio would consume the
  messages it is showing the operator. This is the option ADR-0060 rejected and it stays
  rejected.
- **Keep sampling and tune it.** A shorter interval buys a smaller blind spot and a larger
  load, and never reaches zero. It is what the product does today and what prompted this
  change.
- **A broker plugin or a divert transformer.** Both would let the broker stamp exactly what we
  want onto each message. Both require deploying a class onto every broker, which is precisely
  the operator setup this design exists to avoid, and which no amount of Studio-side work can
  remove.
- **A divert per subscription queue on a multicast address.** Would answer which queue received
  a message. Not expressible for most topologies and a multiplier of broker objects where it
  is. Rejected in favour of saying what capture does and does not know (D5).
- **Persisting the tap into `broker.xml`.** Would make Studio a writer of broker configuration
  files — a much larger commitment than this feature earns. It would also no longer buy what it
  was originally proposed to buy: the tap already survives a restart without it (ADR-0065).
