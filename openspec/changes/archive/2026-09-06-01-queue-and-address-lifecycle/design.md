## Context

See `proposal.md` — Why. This section records only the constraints that shape the
approach.

The existing message-mutation path, which this change parallels rather than
extends:

```
POST /api/v1/clusters/{id}/queues/{q}/messages/actions/{action}?dryRun&override
  → MessageController          unwraps Attempt<Outcome> → 200 / 422 / 4xx
  → MessageService             requireCluster → audit.begin → estimate →
                               cap check → broker call → audit.succeed|fail
  → MessageOperations          one Jolokia exec per method, never dry-run+act
  → JolokiaBrokerClient        per node, behind NodeCallLimiter
```

Constraints that follow from the code as it stands:

- **`MessageService.Outcome` is single-node.** Both arms — `Affected(count, node)`
  and `DryRun(count, cap, overCap, node)` — carry one `node`. A lifecycle result is
  inherently per-node-plural, so it needs its own outcome shape; widening
  `Outcome` would drag the message API into a shape it does not need.
- **`ClusterAccessGuard.requireCluster` throws not-found, not access-denied**, so a
  caller without a grant cannot learn whether a cluster id exists. Lifecycle
  endpoints inherit this and must not leak existence through a different code path.
- **`AuditService.begin/succeed/fail`** writes the row before the broker call and
  updates it after, inside the command transaction. A fan-out has one command and
  N broker calls, so the mapping of audit rows to calls is a decision, not a given.
- **`BrokerMBeans.queue(...)` requires the routing type** to build an object name.
  A queue's routing type is therefore required input for any per-queue operation,
  including delete.
- **`CapabilityProbe` infers `managementWrite` from a read.** Nothing depended on
  that until now.
- **`BrokerListOps.fetch` is the one shared list path** behind all six resource
  views. A newly created queue appears there only after the next scrape tier fires;
  this change does not add a write-through cache.

## Goals / Non-Goals

**Goals**

- One lifecycle command, one audit trail, one per-node truth — no path where the
  UI knows something the audit log does not.
- A partial fan-out failure that is legible after the fact, from the audit row
  alone.
- The same dry-run → cap → confirm contract the operator already knows from
  message operations, with no second vocabulary.

**Non-Goals**

- No rollback. See D3.
- No queue templates, no bulk apply across clusters, no scheduling. Each is a
  separate change if it is ever wanted.
- No writing to `broker.xml`. Everything here is runtime management state, and the
  spec says so where it matters.
- No new transport. Jolokia only, as ADR-0021 already decided for mutations.

## Decisions

### D1 — A lifecycle command targets a cluster and fans out to live nodes

The alternative — require the caller to name a node — is simpler and more honest
about the broker's model, and it was rejected because it makes the common case
(the same queue on every node of a symmetric cluster) a manual loop that an
operator will get wrong under pressure, and because it gives Studio no place to
record "these nodes were supposed to match".

Live nodes come from topology, polled, never from configuration (non-negotiable
#4). A node that is not currently live is not a target and is reported as skipped,
not as failed — it never received the command.

### D2 — The outcome is a per-node list, not a count

```
LifecycleOutcome
  dryRun:  boolean
  nodes:   [ { nodeId, status: WOULD_APPLY|APPLIED|SKIPPED_NOT_LIVE|FAILED|ALREADY,
               affected: long|null, error: string|null } ]
```

`ALREADY` is a success: creating a queue that exists, or deleting one that does
not, leaves the cluster in the requested state. Treating it as an error would make
a re-run after a partial failure impossible, which is the exact situation where a
re-run is what the operator needs.

### D3 — Partial failure is reported, never rolled back

Rejected: apply to all nodes, and on any failure undo the ones that succeeded.

It cannot work for the operation that matters most. A `destroyQueue` that
succeeded on node A cannot be undone — the messages are gone. A compensating
create would produce an empty queue with the same name, which is a *different*
state dressed up as the original, and that is worse than a reported divergence
because it looks like success.

It also has no honest failure mode of its own: the rollback call can fail, leaving
the system in a third state with no one to report to.

So: apply, record each node, return the divergence, and make it visible in the UI
and in the audit row. The operator decides whether to retry, target the failed node
directly, or accept the difference.

### D4 — One audit row per command, carrying the per-node outcome

The alternative, one row per node, makes the fan-out invisible: nothing in the log
says these N rows were one operator action, and a partial failure reads as
unrelated events.

`AuditService.begin` is called once with the cluster, the target and the
parameters; the per-node result list is serialised into the outcome on
`succeed`/`fail`. A fan-out where any node failed is recorded as failed even
though some nodes applied — the outcome detail carries which. Recording it as
success because "most of it worked" is precisely the lie the audit log exists to
prevent.

### D5 — `managementWrite` becomes evidence-backed

Three states, unchanged from ADR-0002:

- `UNKNOWN` — no write has been attempted on this connection. This is the state at
  registration, and it is the honest one. The UI offers lifecycle actions and
  explains that the first one will establish whether the connection can write.
- `AVAILABLE` — a management write has succeeded.
- `UNAVAILABLE` — a management write was refused for an authorization reason, with
  the `broker.xml` snippet naming the missing management permission.

A write that fails for a non-authorization reason (broker unreachable, invalid
argument) does not change the assessment. Conflating "the broker said no to this
argument" with "this connection cannot write" would make one bad request
permanently disable a button.

### D6 — The delete estimate goes through the bulk cap

Deleting a queue destroys its messages. That is a bulk destructive operation
whatever the endpoint is called, so it is counted (`MessageCount` per node,
summed), compared against the cap, and requires `override` above it — reusing the
ADR-0022 machinery rather than inventing a second ceiling. Dry-run reports the
count per node so the confirmation names a real number.

### D7 — The update surface is only what the broker will actually accept

**Revised at apply time against a live broker.** The original text asserted that
both the filter and the routing type are immutable. Only the second half is true.

Measured on Artemis 2.44.0 (the dev pair) through Jolokia — see "Confirmed
management surface" below for the method:

- **Routing type is immutable.** `updateQueue` with a changed `routing-type` is
  refused with `AMQ229211 Can't update queue X with routing type: MULTICAST,
  Supported routing types for address: X are [ANYCAST]`. It stays read-only in the
  UI with the reason, as the spec says.
- **The filter is mutable.** `updateQueue` with a changed `filter-string` is
  accepted and takes effect — the queue's `Filter` attribute reads back as the new
  value. The spec delta's claim that the filter is immutable is wrong and is
  corrected in `specs/queue-lifecycle/spec.md` as part of this change.
- **`updateQueue` is a replace, not a merge.** A config that omits a field
  *clears* it: sending `{"name":"q","max-consumers":7}` against a queue with a
  filter left the queue with `Filter = null`. This is the single most dangerous
  fact in this change — a partial update silently destroys the filter.

  Therefore the update path **reads the queue's current configuration and sends a
  full merged configuration**, never the operator's changed fields alone. The
  service owns that merge; the API accepts a sparse patch and the UI shows the
  effective result before it submits.

The mutable set is consequently every `QueueConfiguration` field except
`routing-type`, `address`, `name` and `durable`. The UI exposes the subset an
operator has reason to change — `filter-string`, `max-consumers`,
`purge-on-no-consumers`, `exclusive`, `non-destructive`, `ring-size` — and the
rest are carried through the merge untouched.

### D8 — Address delete is force-free

`deleteAddress` has a force variant that removes bound queues with it. It is not
exposed. Deleting an address that still has queues is refused with a message
naming the queues, and the operator deletes them explicitly. One click that
destroys an unbounded amount of data with no per-queue count in the confirmation
is not a safe default, and the safe path costs one extra step.

Confirmed on 2.44.0: the force-free `deleteAddress(name)` already refuses with
`AMQ229205 Address X has bindings`. The broker enforces D8 on its own; Studio adds
the queue names, which `AddressControl.getQueueNames()` supplies, because the
broker's own message does not name them.

## Confirmed management surface

Task 1/2 groundwork. Signatures read from the `ActiveMQServerControl`,
`QueueControl` and `AddressControl` interfaces in `artemis-core-client-2.56.0`
(the version this project depends on), and behaviour measured over Jolokia
against the dev broker, Artemis **2.44.0**. Nothing here is from memory.

### Use the JSON API, not the positional overloads

**Every positional `createQueue(...)` and `updateQueue(...)` overload is
`@Deprecated` in 2.56.0.** The current surface takes a `QueueConfiguration` JSON
document:

| Operation | Signature | Notes |
|---|---|---|
| create queue | `createQueue(java.lang.String,boolean)` | `(queueConfiguration, ignoreIfExists)` — returns the resulting config as JSON |
| update queue | `updateQueue(java.lang.String)` | `(queueConfiguration)` — **replaces**, see D7 |
| destroy queue | `destroyQueue(java.lang.String,boolean,boolean)` | `(name, removeConsumers, forceAutoDeleteAddress)` |
| create address | `createAddress(java.lang.String,java.lang.String)` | `(name, routingTypes)` |
| delete address | `deleteAddress(java.lang.String)` | `(name)` — the force-free arm, per D8 |

On `QueueControl` (no arguments, all confirmed working):
`pause()`, `pause(boolean)` (persist), `resume()`, `resetMessageCounter()`, and
the `Paused` / `MessageCount` / `Filter` attributes.

On `AddressControl`: `getQueueNames()` — the bound-queue list D8 needs.

`QueueConfiguration` JSON keys are kebab-case: `name`, `address`, `routing-type`,
`filter-string`, `durable`, `max-consumers`, `purge-on-no-consumers`, `exclusive`,
`non-destructive`, `ring-size`, `auto-create-address`, `group-rebalance`,
`group-buckets`, `group-first-key`, `last-value`, `last-value-key`,
`consumers-before-dispatch`, `delay-before-dispatch`, `auto-delete`,
`auto-delete-delay`, `auto-delete-message-count`, `user`, `enabled`,
`consumer-priority`.

### `ignoreIfExists` gives `ALREADY` for free

`createQueue(config, true)` against an existing queue returns **200 with the
existing configuration** rather than an error. That is exactly D2's `ALREADY`, and
the returned document is what the create path compares against the requested one
to decide `ALREADY` versus `FAILED`-with-the-difference (see Risks) — no separate
read is needed.

### Error codes to classify against

Measured, with the `error_type` Jolokia reports:

| Situation | Code | Classify as |
|---|---|---|
| create queue, already exists (`ignoreIfExists=false`) | `AMQ229019` | `ALREADY` |
| destroy queue, does not exist | `AMQ229017` (`ActiveMQNonExistentQueueException`) | `ALREADY` |
| create address, already exists | `AMQ229204` (`ActiveMQAddressExistsException`) | `ALREADY` |
| delete address, does not exist | `AMQ229203` | `ALREADY` |
| delete address with queues bound | `AMQ229205` | refusal (D8), name the queues |
| update queue, routing-type change | `AMQ229211` | argument refusal (immutable field) |
| invalid filter | `AMQ229020` | argument refusal → 400 |

None of these change the `managementWrite` assessment (D5) — they are argument
refusals, not authorization refusals.

### What an authorization refusal actually looks like

On the console's Jolokia endpoint an unauthorized caller gets **HTTP 403 with no
body**, both for a bad credential and for a valid user whose role lacks access —
and for reads as well as writes. So on this deployment shape D5's authorization
signal is the transport status (401/403), not an in-band `AMQ` code. A broker that
exposes Jolokia without the console's role filter would instead surface
`ActiveMQSecurityException` as a 500 with that `error_type`; the classifier treats
both as authorization refusals and everything else as not.

### A failed create can still leave an address behind

`auto-create-address` defaults to true, so `createQueue` with a bad filter
auto-created the address and *then* failed — leaving a stray empty address. The
create path sets `auto-create-address` explicitly and the create-queue form says
whether the address will be created.

## Risks

- **A fan-out amplifies a bad command by the node count.** Mitigated by dry-run
  being the default in MCP, by the cap applying to the summed estimate, and by the
  per-node preview in the UI naming every node before the action arms.
- **Topology lag.** A node that went live between the topology poll and the
  command does not receive it, and the cluster diverges silently. The outcome list
  names exactly the nodes that were targeted, so the divergence is at least
  visible; the desired-state change (05) is what would detect it later.
- **`ALREADY` hides a mismatch.** A queue that exists with a *different*
  configuration reports `ALREADY` on create. The create path therefore compares the
  existing configuration and reports `ALREADY` only on a match, `FAILED` with the
  difference otherwise.

## Open for refinement

These decisions are the current best answer, not a settled one.

D7 and D8 have now been checked against a live broker and revised above —
D7 materially (the filter turned out to be mutable, and `updateQueue` turned out to
be a replace), D8 confirmed as written. The `queue-lifecycle` spec delta was
corrected to match. D2's status set survived the check unchanged, and
`ignoreIfExists` turned out to implement `ALREADY` natively.

What remains open: whether the update API should accept a sparse patch (and merge
server-side, as D7 now specifies) or require the full configuration from the
client. The former is chosen because it keeps the client honest about what the
operator actually changed, and keeps the dangerous merge in one place.
