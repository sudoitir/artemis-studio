# ADR-0181: Studio enforces universal approver rules, backed by an access-change log

- **Status**: accepted
- **Date**: 2026-10-07
- **Deciders**: Mahdi Amirabdollahi

## Context

An approval provider ([ADR-0179](0179-an-approval-gate-in-the-service-layer-asks-one-provider.md))
decides policy, but some rules make four eyes meaningless if any provider gets them wrong: a
requester approving their own request, a token or agent approving, a stale session approving, or
a requester creating or empowering a helper account while the request waits. Studio already has
step-up within a five-minute window
([ADR-0103](0103-plugin-installer-tier-and-step-up-reauthentication.md)), second factors
([ADR-0143](0143-second-factors-for-local-accounts.md)) and permission scopes
([ADR-0172](0172-teams-own-queues-and-addresses-and-every-check-names-its-resource.md)). Users are
disabled, never deleted. Nothing records who changed whose access, and when, in a form a check can
query.

## Decision

1. **Deciding is session-only.** `POST /api/v1/held-operations/{id}/decision` refuses API tokens
   and agent origins, is CSRF-protected, and needs fresh authentication through the existing
   step-up flow. There is no MCP tool for deciding.
2. **Studio applies these rules for every provider, before the provider's `checkVote`:**
   1. The approver is not the requester (also a database CHECK), and does not share the requester's
      external identity or, case-insensitively, email.
   2. The approver holds the provider's declared approver permission now, at the operation's cluster
      scope.
   3. The approver's account was created before the request.
   4. The requester made no access change to anyone else after the request: no row in the
      access-change log with the requester as actor, another subject, and a later time. Any role,
      team, group-mapping or grant change counts, including indirect ones. This is the strict rule.
   5. The approver's session facts (`authenticatedAt`, `mfaVerifiedAt`, `mfaMethod`) are passed to
      `checkVote`, so a policy can require a second factor.
3. **A hold with no eligible approver is a deny** ("No other user may approve this").
4. **`access_change_log` lives in the security module.** Columns `(at, id, actor_id, subject_id)`;
   `AccessChanges` writes one row in the caller's transaction on every access change; rows are
   kept 45 days, beyond any hold.
5. **Every refusal is audited** and appended to the request's timeline.

## Consequences

- Self-approval, second accounts sharing an identity or email, approval by token or agent, stale
  sessions and freshly made helpers are closed for any provider, whatever its quality.
- The strict rule has a cost: a requester who does routine access work while a request waits
  blocks its approval and must request again. The user chose this over a narrower rule.
- Every access-changing service gains one write; a missed write would weaken rule 4, so the write
  sits in the shared helper the services already call.
- Unrelated identities belonging to the same person are not detectable; policies may restrict
  approvers further.

## Alternatives considered

- **Leave approver rules to the provider.** Rejected: a provider bug would silently reopen
  self-approval.
- **Only block changes that touch the approver permission.** Rejected: indirect grants (a team, a
  group mapping, a default role) are too many paths to reason about; any change by the requester
  blocks.
- **Read access history from the audit trail.** Rejected: audit rows are written for people, with
  free-form parameters, and are not shaped for one indexed check.
