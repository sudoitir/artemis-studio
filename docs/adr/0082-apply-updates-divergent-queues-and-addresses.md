# ADR-0082: Configuration apply updates divergent queues and addresses

- **Status**: accepted
- **Date**: 2026-09-19
- **Deciders**: Artemis Studio maintainers

Extends [ADR-0067](0067-broker-configuration-declared-applied-canary-first.md). D6 calls
`config:apply` a create-and-update authority, but the planner implemented "update" for
settings and diverts only: an existing queue or address that differs from the declaration
becomes a `DIVERGENT_QUEUE` or `DIVERGENT_ADDRESS` finding with no step
(`BrokerConfigPlanner`, whose findings read "An apply never reconfigures an existing queue"
and "An apply does not change an existing address's routing types"). This ADR makes
"update" cover queues and addresses and reverses that planner behaviour. ADR-0067's
decision text stands unchanged.

## Context

ADR-0067 D6 made configuration apply a create-and-update authority, and the planner
applied "update" to settings and diverts but not to queues and addresses. When a declared queue exists with a different
configuration, or a declared address exists with different routing types, the planner
reports a `DIVERGENT_QUEUE` or `DIVERGENT_ADDRESS` finding and plans no step. The operator
is sent to the queue's own edit action instead.

In production this means an apply never converges. A cluster whose queues differ from the
declaration stays in drift after every apply, from the UI and from MCP alike. The operator
declared the shape they want, previewed it, confirmed it, and nothing changed. Drift that
apply cannot close is noise, and an operator learns to ignore it.

Two facts make it safe to change this:

- `QueueLifecycleOperations.updateQueue` already reads the live queue configuration and
  merges the change over it before it sends `updateQueue(String)`. The broker replaces the
  configuration on that call, so without the read a field the declaration does not set
  would be cleared. With the merge, it keeps its live value. This path is already used and
  tested by the queue edit action (ADR-0049).
- `updateAddress(name, routingTypes)` changes an existing address's routing types in place,
  without destroying it or its queues.

Neither call destroys data. The ADR-0067 constraint that an apply never breaks the broker
still holds.

## Decision

**We will make a divergent queue or address a step, not a finding.**

1. **Divergent queue → `REPLACE` step.** It carries before → after for every differing
   key and runs through the queues module's existing read-merge `updateQueue`, never a
   second implementation. Only declared keys are compared and sent. A key the declaration
   does not set keeps its live value, is not reported as drift, and raises no
   `UNINTENDED_KEY_CHANGE` hazard. Removing a key from the declaration therefore does not
   clear it on the broker: clearing a filter or a limit goes through the queue's own edit
   action. The broker cannot change `name`, `address`, `routing-type` or `durable` on a
   live queue. That set is exactly `QueueLifecycleOperations.IMMUTABLE_ON_UPDATE`, and the
   planner reads that constant rather than keeping its own list. A difference in any of
   those fields stays a `DIVERGENT_QUEUE` finding, with no step, even when other keys
   also differ. The finding names the field and explains that changing it means deleting
   and recreating the queue through the queue's own delete flow.
2. **Divergent address → `REPLACE` step** through `updateAddress(name, routingTypes)`.
   The declared routing types are authoritative for an address: the step sends exactly
   the declared set, and an observed set that differs in either direction, a superset
   included, is divergent. Adding a routing type is a **Low** hazard. Removing one with no
   queues of that type bound on the node is **Low**. Removing one that has queues of that
   type bound on the node is a **High** hazard: those queues stop receiving the
   producers' traffic, or the broker refuses the change. Either way the hazard is named,
   and a High one must be acknowledged before any write (ADR-0067 D7).
3. **The replacement steps follow ADR-0067 D3 and D4 unchanged.** They are diff-driven
   (a matching item is `ALREADY` and issues no write), ordered addresses → queues, run on
   the canary first, verified by a re-read, and halt the run on the first failure.
4. **Drift counts a divergent queue or address as drift that apply resolves**, the same
   as a divergent setting. Only the immutable-field case in (1) remains drift that apply
   cannot close, and the drift report says so.
5. **Apply still never destroys a queue or an address.** The D6 rule stands: removing a
   queue or address from the declaration plans no removal, and data is destroyed only
   through the queue's own delete flow and its bulk cap. `config:apply` stays a
   create-and-update authority. The only change is that "update" now covers queues and
   addresses.

## Consequences

Good: apply converges. After an apply, a re-run is all `ALREADY`, and the drift
Studio reports is the drift apply cannot close. The UI and MCP both reach this through
the same planner.

Good: a queue update reuses the tested read-merge path, so the declaration cannot clear a
filter or a limit it does not mention.

Bad: the same read-merge means un-declaring a queue key is silent. Deleting `filter` or
`max-consumers` from a declaration leaves the live value in place, and drift does not
report it, because only declared keys are compared. Clearing a key is a queue edit
action, not an apply.

Bad: an address is the opposite. An address the broker created with both routing types,
declared with one, now gets a removal step on every apply until the declaration lists
both. The plan shows it, and it is High when queues of the removed type are bound.

Bad: an apply can now change the behaviour of a queue that has traffic. A lower
`max-consumers`, a new filter, or a switch to exclusive all affect live consumers. These
changes appear in the plan as before → after and go through the canary, but they are not
free. The hazard catalogue will need to grow as operators find changes that deserve to be
named.

Bad: removing a routing type is a real outage vector. It is High and needs typed
confirmation, and the plan names the bound queues it affects.

Bad: the declaration is now authoritative for existing queues. An operator who edits a
queue with the queue edit action, and leaves the declaration behind, will have that edit
reverted by the next apply. The plan shows the change before the run, but it is still a
reversion.

## Alternatives considered

- **Keep the finding and link to the queue edit action.** This was the status quo, and it
  is what the operator reported as "apply does nothing". An apply that leaves the cluster in
  drift breaks the promise of a declaration.
- **Delete and recreate a divergent queue.** This destroys messages, which D6 rejects
  permanently. It stays the operator's explicit choice through the queue delete flow, and
  only for fields that cannot change in place.
- **A per-plan opt-in to update queues and addresses.** This adds a second switch on top of
  the preview, the hazard acknowledgements and the typed confirmation that already gate
  every write. The before → after in the plan is the disclosure, and the canary bounds the
  blast radius.
