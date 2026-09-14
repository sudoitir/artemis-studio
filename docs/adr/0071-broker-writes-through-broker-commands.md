# ADR-0071: Cluster-wide broker writes run through one audited command executor

- **Status**: accepted
- **Date**: 2026-09-13
- **Deciders**: Mahdi Amirabdollahi
- **Formalises**: [ADR-0022](0022-dry-run-estimate-and-server-enforced-bulk-cap.md), [ADR-0049](0049-cluster-wide-topology-mutation.md)
- **Depends on**: [ADR-0069](0069-kernel-plugin-modular-monolith.md)

## Context

Non-negotiables #2 and #3 require three things of every destructive broker operation:
- it accepts a dry run;
- it writes an `audit_event` in the same transaction, before the broker call, and updates that row with the outcome;
- it is permission-checked.

ADR-0022 adds the server-enforced bulk cap, and ADR-0049 the per-node fan-out with partial outcomes.

The cluster-wide fan-out sequence — guard, targets, audit begin, estimate, dry run, cap, fan out, audit finish, signal — was hand-rolled twice, in `QueueLifecycleService` and in `ConnectionControlService`'s address-scoped close, and the two copies had already drifted: only one recorded write capability, only one treated an uncounted node as unknown. `RoutingService` read the audit repository directly. The guarantee held because each author remembered it.

## Decision

`platform.clusters` exposes `BrokerCommands.run(Command)` for every **cluster-wide** broker write. It lives beside the serving topology and the capability ledger it needs, not in `platform.broker`, which must not depend on cluster registration. A `Command` carries:
- permission, audit action, target type and name, parameters;
- `dryRun` and `override`;
- a per-node action, an optional estimate (what it counts, and whether an uncounted node needs the override), the audit detail shape and the topic signal.

The executor always runs this sequence:
1. `ClusterAccessGuard.requireCluster(permission)`.
2. One target per logical node, liveness from the polled `Active` attribute.
3. `AuditService.begin` in the caller's transaction, before any broker call.
4. Estimate per live node.
5. On a dry run, return `WOULD_APPLY` per node, stating any estimate that could not be made, and finish the audit.
6. Check the cap and refuse with an audited `422` unless overridden.
7. Fan out under the per-node rate limiter; a node's failure is its outcome, never an abort.
8. `AuditService.finish` with per-node detail.
9. Publish the topic signal after commit.

It returns the per-node `LifecycleOutcome`. `noRollbackFor` lives on the executor, so a refusal stays audited.

Three write paths keep their own sequence, because they are not a per-node fan-out, and each still writes its audit row before the broker call:
- message operations and node-scoped connection closes act on the one node the operator named;
- the configuration apply runs canary, read-back verification and halt, with a step cap and hazard acknowledgement;
- message capture's divert reconciliation is Studio's own converge loop, not an operator command.

Mutations that do not touch a broker (users, roles, settings, channels, rules) call `AuditService` directly. Reading audit history goes through `AuditService`, never its repository. `AuditCoverageTest` asserts every public mutating service method in a feature uses `BrokerCommands` or `AuditService`.

## Consequences

- The audit-before-call, dry-run and cap guarantees for cluster-wide commands are structural rather than remembered, and the two former copies now behave the same way.
- Feature services keep only their estimate and per-node action.
- A new cluster-wide write shape extends the executor rather than copying it.
- Single-node and staged writes are held to the same audit ordering by `AuditCoverageTest`, not by sharing code.

## Alternatives considered

- **An AOP `@Audited` aspect.** It cannot see the per-node detail the audit row records, and it hides the "row exists before the broker call" ordering behind proxy rules.
- **One executor for every write shape.** A canary-verify-halt apply and a single-node message operation would each need options the others ignore, and the executor would become the configuration surface it exists to remove.
- **Put the executor in the kernel or in `platform.broker`.** The kernel must not know about brokers, and the broker transport must not know about cluster registration (ADR-0069).
