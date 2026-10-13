# ADR-0190: Audit records reach plugins in commit order, and retention waits for them

- **Status**: accepted
- **Date**: 2026-10-13
- **Deciders**: Mahdi Amirabdollahi

## Context

The audit trail could only be read in Studio. Plugins that forward it elsewhere, sign it or keep
evidence need every record, once it is committed, in one order that every replica agrees on, and
they need retention not to delete what they have not received or are required to keep.

`audit_event.id` is assigned at insert, so a transaction with a lower id can commit after one with a
higher id: a reader tailing by id skips rows. Rows also change after insert: a row starts `PENDING`
and later gets its outcome (ADR-0078), work that outlives its thread keeps a row pending for long
(ADR-0093), and a refusal is recounted for up to a minute. Studio has no leader; installation-wide
work runs once through ShedLock (ADR-0125). Held operations already solved gap-free ordering with a
`(txid, seq)` position read below the oldest running transaction (ADR-0179).

## Decision

1. **Every audit row carries a position.** A trigger stamps `pos_txid = pg_current_xact_id()` and
   `pos_seq` from a sequence on every insert and every change. Readers take rows above a position and
   below `pg_snapshot_xmin(pg_current_snapshot())`, in position order. That is commit order: every
   replica sees the same order and no row is skipped.
2. **A changed row is delivered again.** When a row gets its outcome it moves to a new position.
   A receiver sees the pending record and then the final one; a receiver that is behind sees only the
   latest. `(id, position)` identifies a delivery.
3. **Finished rows are guarded.** A trigger refuses changing anything but the outcome fields, and
   refuses changing a row that already has an outcome, except the count of a refused request.
4. **Studio pushes; receivers acknowledge by returning.** A plugin that declares `audit.receive` in
   its manifest provides one `AuditReceiver`. An installation-wide job calls each receiver with
   batches after its cursor, each call bounded by a timeout on its own virtual thread, and advances
   the cursor only when the call returns. A failing receiver backs off; the audited operation never
   waits for it. Studio owns the cursor, so it knows what each receiver has acknowledged.
5. **Retention waits, up to a limit.** The audit purge does not delete records above the lowest
   acknowledged position unless they are older than `audit.delivery.max-unacknowledged-age`. When it
   does, the receiver gets an explicit gap, an `AUDIT_GAP` audit event is written and the
   `AUDIT_DELIVERY` alert fires. The alert also fires while a receiver is behind by more than
   `audit.delivery.alert-after`.
6. **Plugins can hold records back.** A plugin that declares `audit.retentionHold` provides one
   `AuditRetentionHold` answering time ranges with optional filters. The purge leaves every record a
   hold covers. A hold provider that throws, times out or is not running on the purging replica makes
   that run purge nothing and report why: a hold fails safe.
7. **Both are shown on install.** The manifest flags are capabilities in the install review and need
   their own acknowledgement; a plugin that provides the beans without declaring them is not activated.

## Consequences

- A long transaction anywhere in the database delays delivery until it ends, the same trade-off held
  operations accept.
- A purge can be held up by a receiver that is down, but never longer than the configured age, and
  never silently.
- Every receiver gets the full, unredacted audit trail: the capability is declared and acknowledged.

## Alternatives considered

- **Deliver only finished rows, by id.** Skips rows that commit out of id order, and one long pending
  row stalls the feed.
- **A sequencer job assigning a dense number.** One more writer and its lag, for an order the
  transaction id already gives.
- **Receivers pull with their own cursor.** Studio would not know what was acknowledged, so retention
  could not wait for it.
