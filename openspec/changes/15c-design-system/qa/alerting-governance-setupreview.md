# QA log: alerting-governance-setupreview

Every finding is fixed before this change is archived. Severity: S1 blocks a task or fails AA; S2 broken or misleading; S3 inconsistent; S4 polish.

## Code audit (before)

### alerting-governance-setupreview-1 [S2 · security · page] Alert RulesPanel offers create/edit/toggle/delete with no useCan gating

- Where: `web/src/features/alerting/RulesPanel.tsx:47`
- Evidence: RulesPanel never calls useCan (only NotificationChannels does, for 'alert:write'). RuleForm, the enabled Switch (l.136), the edit pencil and the delete ActionIcon (l.162) all render enabled for a read-only user, who gets a server 403 after acting.
- Fix: const { can, loading } = useCan(); canWrite = !loading && can('alert:write'); hide RuleForm and disable Switch/edit/delete with a reason when !canWrite.
- Status: open

### alerting-governance-setupreview-2 [S2 · reliability · page] Rule enable toggle and delete have no error or success handling

- Where: `web/src/features/alerting/RulesPanel.tsx:138`
- Evidence: update.mutate({ruleId, body}) for the Switch and remove.mutate(r.id) at l.166 pass no onSuccess/onError. A failed toggle or delete is silent, and nothing is announced through aria-live. This breaks the four-outcome mutation rule. Delete also runs on one click with no confirmation.
- Fix: Pass onSuccess/onError that announce the result through a role=status live region (as NotificationChannels does), and confirm the delete.
- Status: open

### alerting-governance-setupreview-3 [S2 · reliability · page] Rule list load failure is shown as 'No rules yet'

- Where: `web/src/features/alerting/RulesPanel.tsx:35`
- Evidence: rulesNotice checks isPending and then data.length===0, never isError. When GET rules fails, data is undefined, so the user is told no rules exist and that the seeded rules can be edited.
- Fix: Add an isError branch that shows rules.error.message before the empty check.
- Status: open

### alerting-governance-setupreview-4 [S2 · reliability · page] Firing panel shows 'Nothing is firing' when the query fails

- Where: `web/src/features/alerting/FiringPanel.tsx:24`
- Evidence: There is no isError branch. A failed or 403 useFiringAlerts leaves data undefined, so the panel renders 'Every enabled rule is currently OK' while alerts may be firing.
- Fix: Render an error state on firing.isError before the empty state.
- Status: open

### alerting-governance-setupreview-5 [S2 · reliability · page] History panel shows 'No firings recorded' on query error

- Where: `web/src/features/alerting/HistoryPanel.tsx:32`
- Evidence: There is no isError check. On an error items=[], so the panel says no firings exist for the cluster.
- Fix: Add a history.isError branch with the error message.
- Status: open

### alerting-governance-setupreview-6 [S3 · reliability · page] Rule create/update outcome only reaches a toast, with no aria-live announcement

- Where: `web/src/features/alerting/RulesPanel.tsx:77`
- Evidence: onSuccess/onError call only notifications.show. Sibling screens (NotificationChannels, governance RulesPanel, SetupReviewView) announce through role=status aria-live. This screen has none, so the outcome is inconsistent for screen-reader users.
- Fix: Add a role=status aria-live region and set its message in both callbacks.
- Status: open

