# ADR-0071: Every broker write runs through one audited command executor

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

Today five services each hand-roll the same sequence: guard, audit begin, estimate, dry run, cap, fan out, audit finish, signal. They are `QueueLifecycleService`, `MessageService`, `ConnectionControlService`, `ClusterService` and `BrokerConfigApplyService`. `RoutingService` writes the audit repository directly. The guarantee holds because each author remembered it. Once features are modules (ADR-0069), feature code must not touch broker clients directly.

## Decision

`platform.broker` exposes `BrokerCommands.run(Command)` as the **only** path to a broker write. A `Command` carries:
- owning feature, audit action and required permission;
- cluster, target and parameters;
- `dryRun` and `override`;
- an estimator, a per-node action and a cap policy.

The executor always runs this sequence:
1. `ClusterAccessGuard.requireCluster(permission)`.
2. `AuditService.begin` in the caller's transaction, before any broker call.
3. Estimate per serving node.
4. On a dry run, return `WOULD_APPLY` per node and finish the audit.
5. Check the cap and refuse with an audited `422` unless overridden.
6. Fan out through `NodeWriter`, under the per-node rate limiter.
7. `AuditService.finish` with per-node detail.
8. Publish the topic signal.

It returns `SUCCEEDED | PARTIAL | FAILED | WOULD_APPLY` with per-node outcomes. `noRollbackFor` lives on the executor, so a refusal stays audited.

`NodeWriter` has a package-private constructor, and Jolokia and Core client types are internal to `platform.broker`. A write outside the executor therefore cannot compile against the module's public types, and Modulith verification rejects the attempt. Broker **reads** stay batched per node through `BrokerReads`.

Mutations that do not touch a broker (users, roles, settings, channels, rules) keep calling `AuditService` directly. `AuditCoverageTest` asserts every public mutating service method in a feature uses one of the two.

## Consequences

- The audit-before-call, dry-run and cap guarantees become structural rather than remembered.
- The five services lose their duplicated orchestration and keep only their estimate and per-node action.
- Step-capped, hazard-acknowledged configuration apply needs a richer `CapPolicy` than a simple count. That complexity now lives in one place.
- A genuinely new write shape (for example streaming progress) extends the executor rather than bypassing it.

## Alternatives considered

- **An AOP `@Audited` aspect.** It cannot see the per-node detail the audit row records, and it hides the "row exists before the broker call" ordering behind proxy rules.
- **Keep per-service orchestration plus a checklist.** This is today's state, and `RoutingService` already drifted from it.
- **Put the executor in the kernel.** The kernel must not know about brokers (ADR-0069).
