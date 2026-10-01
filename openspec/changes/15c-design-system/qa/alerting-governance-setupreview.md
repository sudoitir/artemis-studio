# QA log: alerting-governance-setupreview

Every finding is fixed before this change is archived. Severity: S1 blocks a task or fails AA; S2 broken or misleading; S3 inconsistent; S4 polish.

## Code audit (before)

### alerting-governance-setupreview-1 [S2 · security · page] Alert RulesPanel offers create/edit/toggle/delete with no useCan gating

- Where: `web/src/features/alerting/RulesPanel.tsx:47`
- Evidence: RulesPanel never calls useCan (only NotificationChannels does, for 'alert:write'). RuleForm, the enabled Switch (l.136), the edit pencil and the delete ActionIcon (l.162) all render enabled for a read-only user, who gets a server 403 after acting.
- Fix: const { can, loading } = useCan(); canWrite = !loading && can('alert:write'); hide RuleForm and disable Switch/edit/delete with a reason when !canWrite.
- Status: fixed (`RulesPanel` reads `useCan` (`alert:write` on the cluster; offered while grants load). A reader sees the form, the enabled switch, Edit and Delete visible and disabled, and the reason written above them, where a keyboard user reads it. `RulesPanel.test.tsx` asserts every control disabled with the reason, and fails without the change)

### alerting-governance-setupreview-2 [S2 · reliability · page] Rule enable toggle and delete have no error or success handling

- Where: `web/src/features/alerting/RulesPanel.tsx:138`
- Evidence: update.mutate({ruleId, body}) for the Switch and remove.mutate(r.id) at l.166 pass no onSuccess/onError. A failed toggle or delete is silent, and nothing is announced through aria-live. This breaks the four-outcome mutation rule. Delete also runs on one click with no confirmation.
- Fix: Pass onSuccess/onError that announce the result through a role=status live region (as NotificationChannels does), and confirm the delete.
- Status: fixed (the switch, the form's save and the delete go through `notify`: succeeded, and failed with the cause and the next step, with the control busy while it runs; Delete is a `ConfirmDialog` with `typedName` that states what stops being evaluated and notified, and the keyboard pass (Escape, focus back on the trigger) is asserted. `RulesPanel.test.tsx` covers a failed toggle, a successful and a failed delete)

### alerting-governance-setupreview-3 [S2 · reliability · page] Rule list load failure is shown as 'No rules yet'

- Where: `web/src/features/alerting/RulesPanel.tsx:35`
- Evidence: rulesNotice checks isPending and then data.length===0, never isError. When GET rules fails, data is undefined, so the user is told no rules exist and that the seeded rules can be edited.
- Fix: Add an isError branch that shows rules.error.message before the empty check.
- Status: fixed (the rules are a static `DataTable` with `ErrorState` and Retry in its error slot, so a failed load says why and is never `No rules yet`; `RulesPanel.test.tsx`)

### alerting-governance-setupreview-4 [S2 · reliability · page] Firing panel shows 'Nothing is firing' when the query fails

- Where: `web/src/features/alerting/FiringPanel.tsx:24`
- Evidence: There is no isError branch. A failed or 403 useFiringAlerts leaves data undefined, so the panel renders 'Every enabled rule is currently OK' while alerts may be firing.
- Fix: Render an error state on firing.isError before the empty state.
- Status: fixed (`FiringPanel` is a static `DataTable` with `ErrorState` and Retry before its `EmptyState`; a 403 names `alert:read`; `FiringPanel.test.tsx` asserts `Nothing is firing` is not shown on a failed load)

### alerting-governance-setupreview-5 [S2 · reliability · page] History panel shows 'No firings recorded' on query error

- Where: `web/src/features/alerting/HistoryPanel.tsx:32`
- Evidence: There is no isError check. On an error items=[], so the panel says no firings exist for the cluster.
- Fix: Add a history.isError branch with the error message.
- Status: fixed (`HistoryPanel` is a static `DataTable` with `ErrorState` and Retry, and the shared `Pager` states the position; `HistoryPanel.test.tsx` asserts `No firings recorded yet` is not shown on a failed load)

### alerting-governance-setupreview-6 [S3 · reliability · page] Rule create/update outcome only reaches a toast, with no aria-live announcement

- Where: `web/src/features/alerting/RulesPanel.tsx:77`
- Evidence: onSuccess/onError call only notifications.show. Sibling screens (NotificationChannels, governance RulesPanel, SetupReviewView) announce through role=status aria-live. This screen has none, so the outcome is inconsistent for screen-reader users.
- Fix: Add a role=status aria-live region and set its message in both callbacks.
- Status: fixed (the outcome of a create, save, toggle and delete is `notify.succeeded` or `notify.failed`, polite and assertive through its own roles, so every screen announces the same way; the live-region tests assert it)

