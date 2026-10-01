# QA log: transfer-bulk-metrics

Every finding is fixed before this change is archived. Severity: S1 blocks a task or fails AA; S2 broken or misleading; S3 inconsistent; S4 polish.

## Code audit (before)

### transfer-bulk-metrics-1 [S2 · reliability · page] Bug report stops with no message when the clipboard write fails

- Where: `web/src/features/diagnostics/ReportBugDialog.tsx:69`
- Evidence: `await navigator.clipboard.writeText(body);` has no try/catch. When the body is longer than MAX_ISSUE_URL, a rejected write (permission denied, document not focused, or an insecure http origin where navigator.clipboard is undefined and the call throws a TypeError) causes an unhandled rejection. Neither the notification nor `window.open` then runs, so pressing 'Open issue' appears to do nothing and the user is not told why.
- Fix: Wrap the clipboard write in try/catch. If it fails, still open the issue page and show a notification saying the report could not be copied, with a way to copy the body by hand (for example, show it in a Code block or use Mantine CopyButton).
- Status: fixed (`ReportBugDialog` wraps the clipboard write in try/catch: it opens the issue either way, a written clipboard is announced through `notify.succeeded`, and a refused write or a missing `navigator.clipboard` through `notify.failed` with its cause and the next step, "Copy as Markdown" in the dialog; `ReportBugDialog.test.tsx` covers the written, the refused and the missing clipboard)

