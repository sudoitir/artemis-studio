## Why

Diverts and bridges are how messages actually get from where they are produced to
where they are needed on a real Artemis deployment, and Studio cannot see either
one. Six cross-node resource views cover queues, addresses, consumers, sessions,
connections and producers — every endpoint of a route and none of the routing.

The practical consequence: an operator debugging "messages are going to the wrong
place" or "nothing is arriving on the mirror" has full visibility of both ends of
the problem and none of the middle. The config-diff feature can show that two
nodes' `broker.xml` disagree about a divert, which is useful and is not the same as
seeing what the running brokers are doing.

Adding the read view is straightforward. Adding create and delete is not, and the
reason is the whole point of this change being separate from queue lifecycle: **a
divert created over the management API does not survive a broker restart.** It
exists in the running broker and not in `broker.xml`. That is a genuinely
dangerous shape for an operator who assumes management changes persist — a routing
rule that works until the next restart and then silently stops.

## What Changes

**A new capability, `routing-management`**, in two parts.

**Read, for diverts and bridges.** A cross-node view of both, on the pattern the
six existing views already use, and a `/clusters/{id}/diverts` screen. Bridges are
read-only, permanently: a cluster bridge is cluster-connection machinery, and
creating one at runtime from a management console is not an operation this product
should offer.

**Create and delete, for diverts only, with persistence stated (ADR-0052).** A
divert created through Studio is marked, in the list and at creation time, as
runtime-only. The UI states that it will not survive a broker restart and shows the
`broker.xml` snippet that would make it permanent — the same shape non-negotiable
#5 already uses for missing capabilities, applied here to persistence rather than
availability.

A divert present in the running broker and absent from the effective configuration
is flagged in the list, whether or not Studio created it. That flag is the feature.
Creating diverts is almost secondary to being able to see that someone else's
runtime divert is one restart away from disappearing.

**Fan-out follows change 01.** A divert is per-node like a queue, so create and
delete apply to every live node and report per node, reusing the outcome shape from
`queue-lifecycle` if that change has landed and defining an equivalent one if not.

**One new permission**, `divert:write`. Reading diverts and bridges needs only
`cluster:read`, like every other resource view.

## Impact

- **A new class of honesty problem.** Every capability gate so far answers "can this
  connection do X". This one answers "will what you just did still be true
  tomorrow". If the UI gets that wording wrong, Studio actively misleads an operator
  into a fragile production change.
- **Config-diff gains a natural neighbour.** Runtime-versus-configured divert drift
  is exactly the kind of failover-time surprise `broker-config-diff` exists to
  surface, and the two should be linked rather than duplicated.
- Specs: new `routing-management`; `cross-node-resource-views`, `authorization` and
  `mcp-server` gain requirements.
- ADRs: 0052 (a runtime divert is not a configured divert).
- Not in scope: creating or modifying bridges and cluster connections, and writing
  to `broker.xml`. Studio does not edit broker configuration files — it says what
  would need to change.

## Open for refinement

Proposed, not settled. The strongest open question is whether create and delete
belong here at all, or whether the honest version of this feature is read plus
drift flagging, leaving divert creation to configuration management. That argument
is worth having before any code is written. Revise `tasks.md` and the spec deltas
with whatever is decided.
