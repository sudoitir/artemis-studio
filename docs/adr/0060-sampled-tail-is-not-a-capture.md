# ADR-0060: A live tail is a sample, and says so permanently

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: Artemis Studio maintainers

## Context

The SQL Console (ADR-0058) needs a live mode: an operator watching a queue during a
deploy wants matching messages to appear as they arrive, not to re-run a query every
few seconds.

Artemis offers no non-destructive server push for the messages on an arbitrary queue.
The three mechanisms that exist each fail in a different way:

- A **Core consumer** receives every message and removes it. Studio would be eating
  the operator's traffic to show it to them.
- A **queue browser** is non-destructive and is a snapshot: it walks what is on the
  queue at the moment it is opened, and has no notion of "what arrived since".
- A **divert** to a Studio-owned capture queue is a genuine, complete tap — and it is
  a broker configuration mutation with a cleanup obligation Studio cannot guarantee.
  If Studio dies between creating the divert and removing it, the operator is left
  with a broker quietly copying production traffic into a queue nobody is draining.

The `activemq.notifications` stream, which already drives broker events and
request-reply tracing (ADR-0026), carries lifecycle notifications, not message
bodies. It can say a message was routed; it cannot say what was in it.

So there is no mechanism that is simultaneously complete, non-destructive and free of
broker mutation. The decision is therefore not *how to build a complete tail* — it is
which incomplete thing to build, and what we owe the operator about it.

## Decision

We will implement the tail as **repeated, rate-limited browse against a high-water
mark**, and we will state permanently that it is a sample.

**D1 — Poll, do not consume and do not mutate.** A poller holds a high-water mark on
`(timestamp, messageId)` per target and re-browses at the configured interval,
emitting matches above the mark. It takes a `NodeCallLimiter` permit like every other
management call, so a tail throttles itself against the broker. It changes nothing on
the broker.

**D2 — The consequence is stated, permanently, and cannot be dismissed.** A message
that arrives and is consumed between two observations is never seen. The interface
says so for the whole duration of the tail. Not a toast, not a first-run hint, not a
dismissable banner — a persistent statement, because the operator who most needs it is
the one who has been watching for twenty minutes and has concluded from an empty
result that nothing is arriving.

This is the same obligation as ADR-0044's "the broker wins" and ADR-0056's "every view
is bounded and says so". An observability tool that overstates its own coverage is
worse than one that has less.

**D3 — Where the gap can be measured, report the number.** The broker reports
`MessagesAdded` per queue. The difference between its delta and the rows the tail
emitted is an estimate of what passed through unobserved. A number an operator can
judge beats a warning they have learned to read past.

**D4 — One mechanism, two consumers.** The same poller populates the message index
(ADR-0059). An index subscription is a tail with a persistent sink and no client.
Two pollers with slightly different semantics would eventually disagree about what
"seen" means.

**D5 — A tail is per-query, so it gets its own stream.** The multiplexed cluster
stream (ADR-0003) carries state that is identical for every subscriber. A tail's
payload depends on parameters one client supplied, so it cannot be a topic there. It
is a separate SSE endpoint scoped to that query, applying the same permission check,
the same heartbeat and the same subscriber release, and it ends when the client
disconnects.

## Consequences

- Live mode is genuinely useful on a queue with a backlog or a slow consumer, and
  genuinely lossy on a queue that is drained as fast as it fills. Both are true, and
  the interface has to be honest on the second without being useless on the first.
- The polling interval is a trade between broker load and how much is missed. It is a
  setting, it has a floor, and the floor exists so that a bored operator cannot turn
  it into a load generator.
- Divert-based capture remains the only complete answer and stays available as a
  future change, with the cleanup guarantee as its central problem rather than an
  afterthought.
- The permanent notice will annoy someone who already knows. That is an acceptable
  price for the operator who does not, and who is about to make a decision based on
  an empty screen.

## Alternatives considered

- **A Core consumer on the target queue.** Complete, and destructive. Studio would
  consume the messages it is showing.
- **A divert into a Studio capture queue.** Complete and non-destructive, and it
  mutates broker configuration with a cleanup obligation that survives Studio
  crashing. A real option later, as its own change, with lifecycle as the hard part.
- **Deriving the tail from `activemq.notifications`.** Already subscribed, push-based,
  and carries no message body — so it cannot evaluate the query it is tailing.
- **Polling but calling it live, with no disclosure.** The version that demos best and
  the one that eventually causes an operator to conclude a message never arrived. The
  whole point of this ADR is to refuse it.
