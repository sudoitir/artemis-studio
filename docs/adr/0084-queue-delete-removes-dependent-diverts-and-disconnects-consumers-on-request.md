# ADR-0084: Queue delete removes the diverts that depend on it, and disconnects consumers only on request

- **Status**: accepted
- **Date**: 2026-09-19
- **Deciders**: Artemis Studio maintainers
- **Extends**: [ADR-0049](0049-cluster-wide-topology-mutation.md) (queue lifecycle fan-out),
  [ADR-0071](0071-broker-writes-through-broker-commands.md) (one audited command)

## Context

Queue delete (ADR-0049) sends `destroyQueue(name, removeConsumers=false,
forceAutoDeleteAddress=false)` on every live node. It checks nothing first. It only
estimates the message count for the bulk cap. Operators hit two failures.

**A queue with a consumer cannot be deleted, and the preview does not say so.**
Measured on 2.44 (broker-management-notes §17 Q1), the broker refuses with
`AMQ229025: Cannot delete queue … it has consumers`. Studio does not classify that
refusal, so the node reports a generic bad-response failure. The dry run reported
`WOULD_APPLY` on that same node.

**Deleting a queue leaves the diverts that forward into its address in place, and
they damage the cluster.** Studio never looks at diverts on this path. Measured on
2.44 (§17 Q3, Q4), a divert whose forwarding address has lost its last queue does one
of two things:

- **Auto-create is on for that address.** The next message the divert forwards
  creates the address and the queue again. The operator deleted the queue, and it
  comes back.
- **Auto-create is off and the address is gone.** Every send to the divert's
  *source* address fails. Producers that never used the deleted queue start
  getting errors.

A divert whose *source* is the queue's address is a different case (§17 Q2). The
divert is a binding on that address. It keeps the address alive after its last queue
is destroyed, and stays in place to route what producers send there. Deleting the
queue does not break it.

Studio's own capture taps (ADR-0062) are diverts too. Their prefix is reserved to the
capture subscription that owns them (ADR-0079 D3).

A divert can also be declared somewhere that outlives the delete. The cluster's Studio
declaration (ADR-0067) has a `diverts` section, and a divert in a node's `broker.xml`
is deployed again when that broker starts. Studio cannot tell a `broker.xml` divert
from one created at runtime (ADR-0065 D2).

## Decision

We will make queue delete check each node before it acts, in the preview and again
for real. It gets a preflight, like divert create.

1. **Dependent diverts are removed with the queue, and the preview names them.** A
   divert *depends on* the queue on a node when both of these hold:
   - it forwards into the queue's address;
   - the queue is the last queue bound to that address on that node.

   The preview lists each dependent divert by name, source and forwarding address.
   Typed confirmation (`ConfirmByTyping`) covers the whole blast radius.
   This is not an option. Leaving the divert in place is the damage described above.
2. **A dependent divert that is declared is named as declared, and the preview says
   the removal does not stick.** The preflight matches each dependent divert by name
   against the cluster's declaration. A declared one is still removed, and the node
   warns:
   - in `STUDIO_MANAGED`, the next apply acts on the declaration again. If the
     declaration also declares a queue on the forwarding address, the apply re-creates
     the queue and the divert. If it does not, validation refuses the apply ("Nothing
     consumes at …") until the declaration drops the divert.
   - in `CONFIG_MANAGED`, the `broker.xml` rendered from the declaration still carries
     the divert, and the next deployment and restart bring it back.
   - either way, the warning tells the operator to remove the divert from the
     declaration.

   A divert that is only in a node's `broker.xml` cannot be recognised (ADR-0065 D2).
   So every preview that removes a divert also says: if this divert is in the node's
   `broker.xml`, the broker creates it again at the next restart, and the queue can
   come back with it. Delete is not refused for a declared divert. The declaration is
   the operator's to change, and in `CONFIG_MANAGED` a refusal would not change the
   deployed file either.
3. **On each node, the diverts go first, then the queue.** If a divert delete fails,
   the queue on that node is not deleted, and the node reports `FAILED`. If the queue
   delete then fails, the diverts are already gone. The node reports `FAILED`, and its
   detail names the diverts it removed. There is no rollback: ADR-0049 reports a
   partial failure and never rolls it back. The audit row's per-node detail records
   each removed divert's full configuration, so an operator can recreate it exactly.
4. **Diverts whose source is the queue's address are kept.** The preview names them
   with the reason: they still route what producers send to that address. Deleting
   them would change where live traffic goes. That is not a consequence of deleting
   the queue, so it stays the divert's own delete.
5. **Capture taps are never removed by queue delete.** The ADR-0079 prefix rule
   stands. The subscription resolves its addresses from the queues in Studio's
   scraped snapshot that its pattern matches (`CaptureAddresses.of`). So the preview
   says a tap on the queue's address goes away only when the deleted queue is the last
   queue on that address the subscription's pattern matches. Then the tap is named as
   removed by its capture subscription, once the snapshot no longer holds the queue.
   Otherwise the tap is named as kept, because the subscription still covers the
   address through its other queues.
6. **Disconnecting consumers is opt-in.** The request gets a `disconnectConsumers`
   flag, in REST, the UI and MCP alike, and it defaults to false.
   - **Without the flag,** the preflight reads `ConsumerCount` and refuses a node that
     has consumers. The refusal gives the count and names the flag.
   - **With the flag,** the node is warned with the count and sends
     `removeConsumers=true`. The flag is recorded in the audit params.
   - **A consumer can attach between the preflight and the call.** If the broker then
     refuses with `AMQ229025`, the refusal is classified as a new
     `ManagementRefusal` kind, `HAS_CONSUMERS`, and the node's message names the flag.
7. **The bulk cap is unchanged.** It still counts the summed `MessageCount`
   (ADR-0049). Removing a divert destroys no message.

## Consequences

- Good: deleting a queue no longer makes it reappear through a divert that is only
  runtime state, or break producers on another address. The operator sees every
  divert that goes with it before arming.
- Bad: a divert declared in Studio's declaration or in `broker.xml` is not gone for
  good. The next apply, deployment or restart can bring it back, and the queue with
  it. The preview says so for a divert in the declaration. For `broker.xml` it can
  only say that it cannot tell. Removing the declaration stays the operator's step.
- Good: a queue with consumers fails in the preview with its cause and the next
  action, not in the real run with a raw broker string.
- Bad: one confirmation now removes objects other than the queue. The preview must
  name them, per node, or the confirmation is not informed. The delete dialog shows
  them through `NodeOutcomeSummary`, for the preview and for the result.
- Bad: each node costs one more batched read before the delete (`ConsumerCount`,
  the divert list and the address's bindings). A delete is rare and operator-driven,
  so this is within the broker-friendly budget.
- Bad: a disconnected client that reconnects can recreate the queue, if auto-create
  is on for its address. Studio cannot stop that. The warning with the flag says so.
- A capture tap on a deleted queue's address keeps copying until the snapshot drops
  the queue and a capture pass runs. A stale snapshot row is reaped only when a full
  sweep of the node finishes (`QueueSnapshotUpsert.reapStale`), and a sweep can span
  several scrape ticks (`SweepCursor`). So the bound is the end of the next full
  sweep plus one capture pass, and the copies are bounded by the ring (ADR-0079 D2).

## Alternatives considered

- **Refuse the delete while a dependent divert exists.** This is safe, but the
  operator then deletes the divert through a second screen with a second
  confirmation, to reach a state Studio can name in one preview. We rejected it
  because the operator asked for the cascade, and the preview makes it informed.
- **Also remove diverts whose source is the queue's address.** The implementation
  plan for this work proposed it. Rejected: such a divert stays bound, and keeps its
  address, after the queue is gone (§17 Q2). Removing it changes where live
  producers' messages go.
- **Refuse the delete while a dependent divert is declared.** Rejected: the queue
  would stay undeletable until the operator edits the declaration on another screen,
  and in `CONFIG_MANAGED` that edit does not change the deployed `broker.xml` either.
  Decision 2 names the declared divert and says the removal does not stick.
- **Remove capture taps with the queue and tell the capture subscription.** The
  implementation plan for this work proposed it. Rejected: ADR-0079 reserves the
  capture prefix to the subscription that owns it, and the tap is still wanted while
  the pattern matches other queues on the address. The subscription removes it
  (decision 5).
- **Always send `removeConsumers=true`.** Rejected: it closes a live application's
  consumers, and possibly the application's work, as a side effect of a click. A
  refusal that names the flag costs the operator one more step.
- **Delete the queue first, then the diverts.** Rejected: if the divert delete then
  fails, the node is left in exactly the state this ADR exists to prevent. With the
  diverts first, a failure leaves a queue that no longer receives forwarded copies,
  and the audit row holds what is needed to recreate the diverts.
