# QA log: brokerconfig

Every finding is fixed before this change is archived. Severity: S1 blocks a task or fails AA; S2 broken or misleading; S3 inconsistent; S4 polish.

## Code audit (before)

### brokerconfig-1 [S3 · security · page] Mode Save stays enabled without write permission

- Where: `web/src/features/brokerconfig/ModeControl.tsx:103`
- Evidence: canWrite is only used to render the 'needs the Edit declared configuration permission' hint (line 95); the Save button at line 103 has no disabled={!canWrite}, so a read-only user can submit and only learns from a server 403 alert.
- Fix: Disable Save (and the form controls) when !canWrite, keeping the hint as the reason.
- Status: fixed (`ModeControl` disables the mode select, the report switch, the exclusions and Save without `config:write`, and says why in words beside them; while grants load the controls stay enabled. `ModeControl.test.tsx` asserts the disabled controls and the stated reason, that a permitted save is announced and closes, and that a failed save keeps the popover open with its cause)

