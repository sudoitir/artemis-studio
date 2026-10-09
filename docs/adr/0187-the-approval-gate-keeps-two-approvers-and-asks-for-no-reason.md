# ADR-0187: The approval gate keeps two approvers and asks the requester for no reason

- **Status**: accepted
- **Date**: 2026-10-09
- **Deciders**: Mahdi Amirabdollahi
- **Amends**: [ADR-0179](0179-an-approval-gate-in-the-service-layer-asks-one-provider.md),
  [ADR-0181](0181-studio-enforces-universal-approver-rules-from-an-access-change-log.md)

## Context

**Lock-out.** An approval is a second person's. With one person who holds the approver permission, every
held request has no eligible approver and is refused ("No other user may approve this"), including the
requests that would remove the policy or disable the provider. The installation could be recovered only
by the deployment's break-glass switch (ADR-0184). Studio's own approver rules (ADR-0181) deliberately
stop a lone administrator from making a second approver for themselves, so the way out cannot be a
shortcut inside the gate.

**Reason.** A policy could require a reason from the requester, carried in a header or an MCP argument.
Only Settings and the approvals plugin's own screens had a place to write one; every other action that can
be held failed with `approval-reason-required`, and each plugin action would have needed its own field.

## Decision

1. **The gate needs two approvers while it holds anything.** `ApproverPool` (`@PluginApi`) counts the
   enabled users who hold the armed provider's approver permission for the whole installation;
   `QUORUM` is 2. `ApprovalProvider.enforcing()` (default `true`) lets a provider with no policy yet say that
   it holds nothing, so an installation that is not asking anyone to approve is not held to the quorum.
2. **Prevent, do not recover.** The provider refuses to start holding while the pool is not quorate. Studio
   refuses, before commit, an access change that would leave fewer than two approvers and fewer than there
   were (`ApproverQuorumGuard` on `AccessChanges`, `approver-quorum`, 409). It counts from the tables inside
   the changing transaction, because the access snapshots are dropped only after commit; approval held through
   a team or a directory group is not counted there, so the guard can only miss a refusal, never block a
   safe change. A change that does not lower the count, or any change while the gate is below quorum and
   not made worse, is never refused, so a broken gate can be repaired.
3. **Say so.** `GET /gate/status` reports `enforcing`, `approvers` and `quorate`; Administration and
   Approvals show a notice when the gate holds but is below quorum (a directory sync can do that, and Studio
   cannot refuse a sync). The `NO_APPROVER` refusal names the two ways out: a second approver, or break-glass.
4. **Break-glass stays the recovery.** No fail-open path is added to the gate: a lone administrator must not
   be able to approve themselves, even audited.
5. **The requester's reason is removed.** `GateDecision.Hold.reasonRequired`, the reason on `GateRequest`
   and `GatePreview`, `GateContext.REASON`, the header, the MCP argument, the stored column and the
   exception are gone. A request is the operation, its effect and its policy. An approver still gives a
   reason when rejecting, on the one approval page.

## Consequences

- `Contract.VERSION` is 13; approval providers and plugin UIs are rebuilt.
- A plugin that contributes an action needs no approval-specific field.
- Removing the second-to-last approver is refused with a sentence that says why and what to do.
- A deployment already below quorum is shown the notice, and recovers by granting the permission or by
  break-glass.

## Alternatives considered

- **Let a lone administrator approve their own request, audited.** Rejected: it defeats the rule the gate
  exists for, and a stolen account would be the only approver it needed.
- **Disable the gate when one approver remains.** Rejected: it fails open silently.
- **A reason field per action, contributed by each plugin.** Rejected: the complexity is the problem.
- **An optional note to approvers.** Rejected: approvers already read the operation, its effect and its
  diff; a free field invites the same per-action plumbing.
