## Why

Studio can read every queue in a cluster and mutate every message in one, and
cannot create, delete, pause or reconfigure the queue itself. `MessageService`
ships the whole safety chain — dry-run estimate, ADR-0022 bulk cap, audit row
written before the broker call, typed confirmation in the UI — and
`CrossNodeAggregator` already merges six resource kinds across nodes. Between
those two capabilities sits a hole: the moment an operator needs a queue to exist,
stop, change, or go away, they leave Studio for the `artemis` CLI or a JMX console.

That is the single largest remaining gap between "observability tool" and
"management tool", and it is the one the product name already promises.

Three things make it more than a fifth verb on the message API:

1. **Artemis cluster nodes each own their own queues.** There is no cluster-scoped
   create. "Create this queue on the cluster" is N management calls with real
   partial-failure semantics, and no amount of API shape hides that.
2. **`managementWrite` is currently inferred, not proven.** `CapabilityProbe`
   decides a connection can write by making a successful *read-only*
   `listNetworkTopology()` call. Today nothing depends on that inference. The
   moment a create button depends on it, the capability model is lying to the
   operator — a direct violation of non-negotiable #5.
3. **Deleting a queue destroys data that no dry-run can restore.** Message
   operations are reversible in spirit (a moved message still exists). A destroyed
   queue and its messages are not.

## What Changes

**A new capability, `queue-lifecycle`,** covering, for queues: create, delete,
update the mutable configuration (max-consumers, purge-on-no-consumers, ring
size), pause, resume, and reset the message counter; and for addresses: create and
delete. Filters and routing type are immutable on an existing queue and the
capability says so rather than offering an edit that the broker will refuse.

**Fan-out is to every live node (ADR-0049).** A lifecycle command names a cluster,
not a node. Studio resolves the live nodes from topology — never from configuration
(non-negotiable #4) — and applies the operation to each. The result is a per-node
outcome list, not a single boolean.

- `?dryRun=true` returns the target node list and, for a delete, the message count
  that would be destroyed on each node, without touching any broker.
- A partial failure is **reported, not rolled back**. Studio records what succeeded
  where, surfaces the divergent state, and leaves the operator in control.
- The delete estimate is checked against the bulk cap, so destroying a queue
  holding more than the cap requires the same `override` as any other bulk
  mutation.

**Capability honesty.** `managementWrite` stops being inferred from a read. It is
`UNKNOWN` until a write has actually been attempted, and a refused write records
`UNAVAILABLE` with the `broker.xml` snippet that grants the missing management
permission. The UI shows lifecycle actions as explained-and-disabled, never
silently absent.

**Four new permissions** — `queue:create`, `queue:delete`, `queue:update`,
`queue:pause` — checked through the existing `ClusterAccessGuard.requireCluster`
scope walk, so a grant can be global, per-environment or per-cluster with no new
mechanism.

**One MCP tool**, `queue_lifecycle`, with a kind discriminator, `dryRun` defaulting
to true and a `confirm` argument that must equal the queue or address name before
a destructive kind executes — the same contract `queue_action` already implements.

**The UI** gains a create action on the queues screen and pause / resume / edit /
delete on the queue detail drawer, each showing the per-node dry-run before it
arms.

**A new capability, `operator-ui`,** records the interaction contract these screens
must meet, because this change introduces three things the product has not had
before: a real form, a destructive action whose blast radius is a list of nodes
rather than a number, and a control that must explain why it is disabled. The
contract covers naming a blast radius before arming a confirmation, rendering all
four mutation outcomes rather than the happy path, presenting a per-node result as
one legible object, explaining an unavailable capability in place of the action,
form labelling and blur-time validation, empty states that teach, and keyboard
completeness for destructive flows. It is written once here and referenced by the
changes that follow rather than restated in each.

**Per-node broker settings become readable** (folded in during apply, at the
operator's request). Studio could compare two nodes' configuration but had no way
to answer "what is this one node actually running with" — the question asked
first. `ConfigReader` already read exactly that in one batched call for the diff's
benefit, with no endpoint of its own. It now has one, and is exposed to an MCP
client as a **resource** (`cluster://{id}/nodes/{nodeId}/settings`) rather than a
tool, because it is something to look at rather than an action to take — and
because a resource costs nothing in the tool listing every conversation pays for.

**The MCP listing budget becomes a per-tool average (ADR-0050).** The flat 2000
-token ceiling had been calibrated against the thirteen tools that existed and had
no headroom, so *any* new capability would have failed it. Enum members and JSON
body shapes moved out of the tool schemas into a `studio://tools` resource that a
model reads only once it has chosen a tool, and the ceiling now scales with tool
count. No tool was removed to make room.

## Impact

- **First write path to a broker that is not a message operation.** Every
  management call Studio has made until now was a read or a message mutation. This
  is a new class of blast radius and is why it carries its own ADR.
- **Behaviour change:** a connection whose `managementWrite` was reported
  `AVAILABLE` on inference alone may now report `UNKNOWN`. That is a correction,
  not a regression — the previous value was not evidence-backed.
- **Audit:** four new action types, each recording the per-node outcome, so a
  partially applied fan-out is reconstructable after the fact.
- Specs: new `queue-lifecycle` and `operator-ui`; `broker-capabilities`,
  `authorization` and `mcp-server` gain requirements.
- **The `operator-ui` contract applies to screens that already exist**, not only to
  new ones. Bringing them to it is explicitly out of scope here — the contract is
  stated, the new screens meet it, and existing screens are brought up as they are
  touched. Retrofitting the whole frontend inside this change would bury the
  feature.
- ADRs: 0049 (cluster-wide topology mutation), 0050 (MCP progressive disclosure,
  extends 0045).
- **New endpoint and MCP resource for per-node settings**, and a corrected
  `queue-lifecycle` spec: the filter turned out to be *mutable* on a live queue,
  and `updateQueue` turned out to replace rather than merge. Both were measured
  against a live broker during apply, not assumed.
- Not in scope, deliberately: diverts and bridges (their own change — a runtime
  divert has a persistence problem queues do not), and any notion of a declared
  desired state (its own change, and it depends on this one).

## Open for refinement

This proposal is a starting position, not a frozen contract. It is expected to be
brainstormed further before and during `/opsx:apply` — in particular the mutable
update surface, the exact shape of the per-node outcome, and whether address
delete belongs here at all. `tasks.md` and the spec deltas in `specs/` should be
revised whenever that discussion changes the answer; an out-of-date task list is a
worse outcome than an edited one.
