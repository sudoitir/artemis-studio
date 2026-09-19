# ADR-0083: Apply keeps a routing type that a bound queue uses

- **Status**: accepted
- **Date**: 2026-09-19
- **Deciders**: Artemis Studio maintainers
- **Amends**: [ADR-0082](0082-apply-updates-divergent-queues-and-addresses.md) D2, the
  case where a removed routing type has queues of that type bound on the node.

## Context

ADR-0082 D2 made the declared routing types authoritative for an address. It planned a
`REPLACE` step through `updateAddress` even when the declaration drops a routing type
that has queues of that type bound on the node, and named that step a High hazard.

The broker refuses that change. Measured on 2.44 (broker-management-notes §15 M8), it
answers `AMQ229209: Can't remove routing type …, queues exists for address` and leaves
the address unchanged. So the step fails every time. Addresses are ordered first
(ADR-0067 D3), and a failure halts the run on the canary. No queue, setting or divert
step on that node runs, and no other node is attempted.

The case is common. An address the broker auto-created with both routing types, declared
with one, and holding an undeclared subscription queue of the other, was `ALREADY`
before ADR-0082. Under D2 as written, every apply of that cluster fails, from the UI,
MCP and schedules alike, and blocks unrelated changes until the declaration is edited.
A step the planner knows will fail is not an honest plan.

## Decision

**A removed routing type that has queues of that type bound on the node is kept, and
reported as a `DIVERGENT_ADDRESS` finding, not planned as a step.**

1. The finding names the routing type, the bound queues, and the remedy: declare the
   type, or delete those queues with their own delete action and apply again.
2. The rest of the address still converges. The step sends the declared routing types
   plus the kept ones. If that equals what the broker holds, there is no step.
3. Adding a routing type, and removing one with no queue of that type bound, stay `Low`
   hazard steps as in ADR-0082 D2. The High `ROUTING_TYPE_CHANGE` hazard is gone,
   because no step that raises it is ever planned.
4. Drift reports the finding as drift apply cannot close, like the immutable-field
   queue case in ADR-0082 D1.

## Consequences

Good: an apply of a cluster with such an address proceeds and converges everything
else. The operator sees one finding with the exact queues and the way to close it.

Good: the plan no longer contains a step that is known to fail.

Bad: the declaration is no longer fully authoritative for an address's routing types.
A bound type survives an apply until the operator acts, and drift keeps reporting it.

## Alternatives considered

- **Keep the step and its High hazard (ADR-0082 D2 as written).** The broker refuses
  the change, so the step halts the node before any other step. The operator gets a
  failed run instead of a converged one.
- **Keep the step and order it last on the node.** The other steps of that node would
  run, but the run still fails, the other nodes are still never attempted, and a
  scheduled apply fails every time.
