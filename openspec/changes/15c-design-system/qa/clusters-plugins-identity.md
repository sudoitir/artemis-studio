# QA log: clusters-plugins-identity

Every finding is fixed before this change is archived. Severity: S1 blocks a task or fails AA; S2 broken or misleading; S3 inconsistent; S4 polish.

## Code audit (before)

### clusters-plugins-identity-1 [S3 · performance · page] Plugins inventory polls every second, with no backoff, for as long as any error lasts, 403 included

- Where: `web/src/features/plugins/api.ts:58`
- Evidence: refetchInterval: (query) => (busy(query.state.data) || query.state.error ? 1_000 : 30_000). Any error sets the interval to 1 s and nothing stops it. The retry option skips 403, but refetchInterval does not. A user without the plugins permission, or a server that keeps returning 5xx, gets a request every second for as long as the panel or HeaderIndicator is mounted. Each poll is a full retry cycle of up to 3 attempts on non-403 errors.
- Fix: Return false when the error status is 403. For other errors, back off with an interval based on query.state.errorUpdateCount (for example min(1000*2^n, 30000)), and keep the fixed 1 s only while busy(data) or while a restart is known to be in progress.
- Status: open

