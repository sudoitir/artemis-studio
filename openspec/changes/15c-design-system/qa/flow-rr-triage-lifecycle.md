# QA log: flow-rr-triage-lifecycle

Every finding is fixed before this change is archived. Severity: S1 blocks a task or fails AA; S2 broken or misleading; S3 inconsistent; S4 polish.

## Code audit (before)

### flow-rr-triage-lifecycle-1 [S1 · a11y · page] Enabled Switch per expectation row has no accessible name

- Where: `web/src/features/rr/ExpectationsView.tsx:235`
- Evidence: <Switch checked={e.enabled} onChange={() => toggle(e)} size="sm" /> has no label or aria-label, so a screen reader announces only 'switch, on' with no address. Fails WCAG 4.1.2 Name, Role, Value.
- Fix: Add aria-label={`Trace ${e.requestAddress}`}, as the remove button next to it already does.
- Status: open

### flow-rr-triage-lifecycle-2 [S2 · reliability · page] Expectation toggle has no pending guard, so quick double toggles send the same value twice

- Where: `web/src/features/rr/ExpectationsView.tsx:127`
- Evidence: toggle() builds enabled: !e.enabled from the row data from before the mutation, and the Switch is never disabled while update.isPending. Two clicks before the refetch send two PUTs with the same enabled value, so the switch ends up in a state the user did not choose.
- Fix: Disable the Switch while update.isPending for that id, or pass enabled from the Switch's onChange event (currentTarget.checked) instead of negating the old row.
- Status: open

### flow-rr-triage-lifecycle-3 [S2 · reliability · page] Remove-expectation button deletes at once, with no confirmation and no pending state

- Where: `web/src/features/rr/ExpectationsView.tsx:242`
- Evidence: onClick calls remove.mutate(e.id) straight away. There is no confirmation and no loading or disabled state. One misclick stops tracing for that address and loses its settings. A double click sends two DELETEs, and the second returns 404, which shows a red error toast after the green 'Stopped tracing' toast.
- Fix: Ask for confirmation, and pass loading/disabled from remove.isPending && remove.variables === e.id.
- Status: open

### flow-rr-triage-lifecycle-4 [S3 · security · page] The create, toggle and remove controls for expectations are not gated with useCan

- Where: `web/src/features/rr/ExpectationsView.tsx:182`
- Evidence: Only lifecycle/RetentionTable.tsx calls useCan in these four features. ExpectationsView shows the create button, the enabled Switch and the remove button to every user. A read-only user only finds out through a 403 toast after acting.
- Fix: Use useCan() to hide or disable the create, toggle and remove controls when the user lacks the write permission, as RetentionTable does.
- Status: open

### flow-rr-triage-lifecycle-5 [S3 · reliability · page] The 'Warn at' field becomes 0 when cleared, below its own minimum of 1

- Where: `web/src/features/lifecycle/PolicyDialog.tsx:83`
- Evidence: onChange={(v) => setWarn(Number(v) || 0)} on a NumberInput with min={1}. Clearing the field stores 0, and Save sends quotaWarnPercent: 0 with no check on the client. The server either rejects it with a generic 'Not saved' error or stores a warning threshold of 0%.
- Fix: Fall back to store.quotaWarnPercent or 1 instead of 0, and check the range before mutate.
- Status: open

