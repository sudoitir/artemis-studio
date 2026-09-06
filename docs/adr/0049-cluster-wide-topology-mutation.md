# ADR-0049: Queue and address lifecycle is a cluster-wide fan-out that reports per node

- **Status**: accepted
- **Date**: 2026-09-06
- **Deciders**: Artemis Studio maintainers

## Context

Studio could read every queue in a cluster and mutate every message in one, and
could not create, delete, pause or reconfigure the queue itself. That is the
largest remaining gap between an observability tool and a management tool, and
the one the product name already promises.

Three forces make it more than a fifth verb on the message API.

**Artemis cluster nodes each own their own queues.** There is no cluster-scoped
create. "Create this queue on the cluster" is N management calls with genuine
partial-failure semantics, and no API shape hides that.

**This is the first write to a broker that is not a message operation.** Every
management call Studio had made until now was a read or a message mutation. A new
class of blast radius deserves its own recorded decision.

**Destroying a queue destroys data no dry run can restore.** A moved message still
exists somewhere; a destroyed queue and its messages do not.

Two facts about the broker, measured against Artemis 2.44 during this change
rather than carried from memory, shape the implementation:

- Every positional `createQueue` / `updateQueue` overload is deprecated as of
  2.56. The current surface takes a `QueueConfiguration` JSON document.
- **`updateQueue` replaces rather than merges.** A document that omits a field
  clears it — sending only the fields an operator changed silently destroys the
  queue's filter.

## Decision

**A lifecycle command targets a cluster, not a node**, and fans out to every live
node. Live nodes come from polled topology, never from stored configuration
(non-negotiable #4). A primary and its synced backup are one logical node and are
targeted once, at whichever endpoint is currently active.

**The result is a per-node list, not a count.** Each node reports `WOULD_APPLY`,
`APPLIED`, `ALREADY`, `SKIPPED_NOT_LIVE` or `FAILED`. `ALREADY` is a success:
creating a queue that exists, or deleting one that does not, leaves the cluster in
the requested state, and treating it as an error would make a re-run after a
partial failure impossible — which is exactly when a re-run is what the operator
needs. A node that was not live is *skipped*, not failed: it never received the
command.

**A partial failure is reported, never rolled back.** A `destroyQueue` that
succeeded on node A cannot be undone. A compensating create would produce an empty
queue with the same name — a different state dressed up as the original, which is
worse than a reported divergence because it looks like success. Studio records
what succeeded where, surfaces the divergence, and leaves the operator in control.

**One audit row per command**, carrying the per-node outcome in a new
`audit_event.outcome_detail` JSONB column. One row per node would make the fan-out
invisible, and a partial failure would read as unrelated events. A command that
failed on any node is recorded as failed, with the detail naming which nodes
applied — recording it as success because most of it worked is the lie the audit
log exists to prevent.

**`managementWrite` becomes evidence-backed.** It was inferred from a read-only
`listNetworkTopology()` succeeding, which proves only that Jolokia is not under a
read-only policy. Nothing depended on that until a create button did. It is now
`UNKNOWN` until a write has been attempted, `AVAILABLE` once one has succeeded,
and `UNAVAILABLE` once one has been refused *for an authorization reason*, with
the `broker.xml` that would grant it. A write that fails for any other reason
leaves the assessment alone, so one malformed request cannot permanently disable a
button. The evidence is persisted on the cluster row, because the probe itself
makes no write and must not.

**Destroying a queue goes through the ADR-0022 bulk cap**, counted as the summed
`MessageCount` across target nodes, because it destroys those messages whatever
the endpoint is called.

**Address delete is force-free.** `deleteAddress` has a force variant that removes
bound queues with it; it is not exposed. One click that destroys an unbounded
amount of data with no per-queue count in the confirmation is not a safe default,
and the safe path costs one extra step.

**The update path reads before it writes.** Because `updateQueue` replaces, the
service reads the queue's current configuration, applies the operator's patch over
it, and sends the whole document. The API accepts a sparse patch; the dangerous
merge lives in one place.

**Routing type is immutable; the filter is not.** The broker refuses a routing-type
change with `AMQ229211` and accepts a filter change. The UI presents routing type
as immutable with the reason, and offers the filter as editable.

## Consequences

Operators can manage queue topology without leaving Studio for the `artemis` CLI
or a JMX console, and a partially applied fan-out is legible both in the UI and,
afterwards, from a single audit row.

A connection whose `managementWrite` was reported `AVAILABLE` on inference alone
now reports `UNKNOWN` until its first write. That is a correction, not a
regression — the previous value was not evidence-backed — but it is a visible
behaviour change and the changelog says so.

A fan-out amplifies a bad command by the node count. Mitigated by dry-run being the
default over MCP, by the cap applying to the summed estimate, and by the UI naming
every target node before the action arms.

Topology lag remains: a node that goes live between the poll and the command does
not receive it, and the cluster diverges. The outcome list names exactly the nodes
targeted, so the divergence is visible; detecting it later is the desired-state
change's job, not this one's.

We are committed to the per-node outcome shape in the API, in MCP, and in the
audit row. Widening `MessageService.Outcome` to match was rejected — it is
single-node for good reason.

## Alternatives considered

**Require the caller to name a node.** Simpler, and more honest about the broker's
model. Rejected because it makes the common case — the same queue on every node of
a symmetric cluster — a manual loop an operator will get wrong under pressure, and
because it gives Studio no place to record "these nodes were supposed to match".

**Apply everywhere, roll back on any failure.** Rejected: it cannot work for the
operation that matters most, since a destroyed queue cannot be restored, and the
rollback call can itself fail, leaving a third state with no one to report to.

**One audit row per node.** Rejected: nothing would say those N rows were one
operator action.

**Keep inferring `managementWrite` from a read.** Rejected as a direct violation of
non-negotiable #5 the moment anything depends on it.

**Probe management-write by performing a harmless write.** Rejected: there is no
harmless write. Creating and destroying a probe queue is a side effect nobody asked
for, on a production broker, to answer a question the next real command answers for
free.
