# QA log: messages-events-audit

Every finding is fixed before this change is archived. Severity: S1 blocks a task or fails AA; S2 broken or misleading; S3 inconsistent; S4 polish.

## Code audit (before)

### messages-events-audit-1 [S2 · security · page] Send and Purge queue buttons are not permission-gated via useCan

- Where: `web/src/features/messages/MessagesView.tsx:275`
- Evidence: The header renders <Button onClick={() => setSendOpen(true)}>Send</Button> and <PurgeQueue .../> with no useCan check. rowActions.tsx (lines 26 and 122) gates the per-row actions with useCan. A read-only operator therefore sees and can open Purge, which fires a dry-run purge mutation as soon as it is clicked (line 114), and can open Send. Both then fail with a server 403 that only appears as a toast. The header is also rendered above the 'Message operations are not available here' alert (line 288), so the destructive buttons still show when messaging is UNAVAILABLE.
- Fix: Gate Send and PurgeQueue on can('message:send') and can('queue:purge'), or on the matching permission ids, the same way rowActions does. Hide them, or disable them with the reason shown, while useCan is loading, when the permission is missing, or when gated is true.
- Status: fixed (Send and Purge queue are gated on message:send and queue:purge and the cluster's messageIo capability: visible, disabled, with the reason in a popover; offered while grants load)

### messages-events-audit-2 [S3 · security · page] Selector bulk actions menu is not gated via useCan

- Where: `web/src/features/messages/MessageActions.tsx:74`
- Evidence: The 'By selector…' menu offers move, retry, delete and expire, plus bulk dry-run and execute, with no useCan check. The same actions on rows are gated in rowActions.tsx. An operator who lacks those permissions is offered destructive bulk operations that the server will reject.
- Fix: Filter the menu items with can() from useCan, as rowActions does.
- Status: open

