# ADR-0078: The audit row commits before the broker call, in its own transaction

- **Status**: accepted
- **Date**: 2026-09-14
- **Deciders**: maintainers

## Context

Non-negotiable #3 required every mutating call to write an `audit_event` "in the same
transaction as the command, before the broker call, updated with the outcome".
`AuditService.begin` saved the pending row inside the caller's transaction, so the row
existed only in that uncommitted transaction until the whole command finished.

A broker is not a participant in that transaction, and two failures follow from treating
it as one:

- **A crash mid-command leaves no record.** If the process stops after a broker call has
  changed state (one node of a fan-out, or an id already moved), the transaction rolls
  back and the audit row disappears. The broker is changed, and the audit trail says
  nothing happened. The row that was "written before the broker call" was never durable
  before it.
- **A fan-out holds a database transaction across broker calls.** It keeps a pooled
  connection open for N nodes × up to the read timeout, which contradicts the
  scrape-scheduling rule that network I/O is outside database transactions.

## Decision

We will commit the pending audit row in its own transaction before any broker call, and
record the outcome (success, failure, or failure with the partial affected count) in a
second, separate transaction after the call.

If the caller's own transaction rolls back before an outcome was recorded, a transaction
synchronisation marks the row as failed with that reason. So a pending row is never left
behind by an action that did not happen.

Commands that fan out across nodes, and message mutations, no longer run their broker
calls inside a database transaction. Their local state writes run in short transactions of
their own.

Non-negotiable #3 is reworded to: "Every mutating call commits an `audit_event` before the
broker call and updates it with the outcome."

## Consequences

- The audit trail records every broker call that was issued, including ones whose
  process died before recording an outcome; those rows stay `PENDING`.
- An audit row can no longer be rolled back together with the action. A database-only
  action that fails leaves a failure row rather than nothing. That is more history, not
  less.
- The audit write briefly needs a second pooled connection while a caller's transaction
  is open. Callers that fan out hold no outer transaction any more, so the peak is
  unchanged in practice.
- Tests that expected an audit row to vanish with a rolled-back action now see a failure
  row.

## Alternatives considered

- **Keep one transaction.** It loses the record of exactly the commands most in need of
  one (those interrupted mid-way), and holds connections across network calls.
- **Transactional outbox.** It solves delivery to another system. Here the row itself
  must exist before the call, and the outbox adds a relay for no gain.
- **Write the row in its own transaction, but only for broker commands.** It would leave
  two audit semantics to reason about. One rule for every mutating call is easier to hold.
