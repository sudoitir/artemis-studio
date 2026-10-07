# ADR-0180: Held operations are sealed, claimed once and replayed as the requester

- **Status**: accepted
- **Date**: 2026-10-07
- **Deciders**: Mahdi Amirabdollahi

## Context

A held operation ([ADR-0179](0179-an-approval-gate-in-the-service-layer-asks-one-provider.md)) may
wait days before it runs, on any replica. It must run exactly as requested, at most once, and only
if the requester could still run it. Plugins share Studio's database role
([ADR-0103](0103-plugin-installer-tier-and-step-up-reauthentication.md)), so constraints alone do
not stop a row from being edited. Some parameters are secret (a new user's password), and some
results are secret (a new API token). A replica can crash in the middle of a purge, and nobody can
tell how far it got except from the audit row
([ADR-0078](0078-audit-row-commits-before-the-broker-call.md)).

## Decision

1. **Parameters are bound by a hash.** Parameters are written as canonical JSON (sorted keys, nulls
   dropped, floats refused, instants in UTC, lowercase UUIDs, NFC strings, at most 64 KB), and
   `params_hash = SHA-256(type ‖ LF ‖ version ‖ LF ‖ canonical)`.
2. **The request and the decision are sealed.** `sealed_payload` holds the canonical parameters,
   hash, requester, auth kind, token id, policy digest and state key; `sealed_decision` holds the
   approver, vote and time. Both are AES-GCM seals from `SecretVault` with the row id in the
   associated data, under a key kept outside the database, and take part in key rotation
   ([ADR-0132](0132-envelope-encryption-key-providers-and-rotation.md)). Before a run both are
   opened and the hash recomputed; any mismatch refuses with `integrity`. The payload is wiped at
   a terminal state; approvers see a redacted copy.
3. **Triggers keep the state machine.** `HELD→APPROVED|REJECTED|CANCELLED|EXPIRED`,
   `APPROVED→EXECUTING|CANCELLED|EXPIRED`, `EXECUTING→SUCCEEDED|FAILED|REFUSED|OUTCOME_UNKNOWN`;
   identity columns are immutable; the payload may only become NULL, and only at a terminal state.
   An append-only event table is the timeline and a gap-free outbox for providers. Both tables are
   managed stores ([ADR-0134](0134-one-data-lifecycle-for-every-store.md)); terminal rows are kept
   90 days, open rows never purged.
4. **A vote is one conditional update.** It must echo the stored hash and version, so the approver
   proves they saw exactly this request; the update requires `state='HELD'`, the version and
   `expires_at > now()`. No row back is `409`.
5. **A run is claimed once.** `UPDATE … SET state='EXECUTING' … WHERE state='APPROVED' AND
   run_deadline > now()`: under READ COMMITTED one claimer wins. The approving replica starts the
   run; a ShedLock job ([ADR-0125](0125-installation-wide-jobs-run-once-through-shedlock.md)) picks
   up any it missed.
6. **The run replays as the requester.** The provider must still be armed and the type version
   unchanged; the requester's principal is rebuilt (empty for a disabled user or a revoked token,
   which is `REFUSED`); the effect is estimated again and its `stateKey` must be equal; the
   provider's `checkRun` must agree. The same public service method then runs under a one-use
   replay ticket, with the request's audit row as parent and the approval on the new row, so a
   permission revoked meanwhile also ends in `REFUSED`.
7. **Two modes.** `ON_APPROVAL` runs in the background as above. `BY_REQUESTER`, for an operation
   whose result is secret, runs only when the requester resubmits the same operation before the
   run deadline, inside their own request.
8. **An interrupted run is never rerun.** An `EXECUTING` row whose replica is gone and whose 15
   minute lease has passed becomes `OUTCOME_UNKNOWN`. The requester asks again.
9. **All time is the database's.** Votes, claims and expiry compare with `now()`; providers return
   durations, never instants.

## Consequences

- Tampering with a row, replaying a vote and racing two approvers or two replicas all fail closed.
- A requester who lost a permission, was disabled or revoked the token cannot have it used for them.
- A crash mid-run leaves a visible `OUTCOME_UNKNOWN` instead of a guessed second purge, at the cost
  of a manual retry.
- A Studio upgrade that changes a type's version refuses its approved requests ("request again").
- Secrets in parameters live only in the seal and only until the operation ends.
- Every gated parameter type must canonicalize; a float parameter is a design error caught early.

## Alternatives considered

- **Integrity from database constraints alone.** Rejected: the role is shared, so constraints are
  not a boundary.
- **Run as the approver.** Rejected: the approver may hold permissions the requester lacks, which
  turns approval into escalation.
- **Retry an interrupted run.** Rejected: a destructive operation run twice is worse than one that
  must be requested again.
- **Return the secret result to the approver or store it.** Rejected: `BY_REQUESTER` keeps the
  secret in the requester's own response and nowhere else.
