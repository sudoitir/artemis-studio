## Why

A security review of the browser and plugin surface found gaps. A request with any `Authorization` header skips the CSRF check, even when that header authenticates nothing. Disabling a user or taking away their grants leaves their browser session working for up to eight hours. Studio sends no Content-Security-Policy, and a plugin's SVG assets are served without a sandbox. CI has no dependency or static security scan. With several instances on one database, every scheduled job also runs on every instance, so housekeeping and partition maintenance run once per instance instead of once.

## What Changes

- Only a valid API bearer token skips the CSRF check. Any other `Authorization` header is rejected with 401.
- Sessions are stored in the database as intended: the Boot 4 session module was missing, so they lived in memory. Disabling a user, removing a grant or changing a role's permissions ends the affected users' sessions.
- Every response carries a Content-Security-Policy that allows scripts only from Studio's own origin and forbids framing. Plugin SVG assets are sandboxed.
- CI runs CodeQL and OSV-Scanner on pull requests, on `main` and weekly.
- **BREAKING (plugin API)**: every `ScheduledJob` declares a `Scope`, either `INSTANCE` or `INSTALLATION`. Installation-wide jobs run once across instances, through ShedLock on Studio's database. `Contract.VERSION` is bumped.

## Capabilities

### Modified Capabilities
- `identity-and-sessions`: CSRF for non-bearer requests, sessions ended on revocation, the Content-Security-Policy.
- `operational-health`: job scope and run-once jobs, reported as skipped elsewhere.

## Impact

- **Security**: `SecurityConfig`, `BearerAuthenticationFilter`, `UserService`, `RoleService`, `PluginAssetController`.
- **Jobs**: `kernel/jobs` (`ScheduledJob`, `JobScheduler`, `JobStatuses`), `ScrapeScheduler`, every `*Jobs` class, the plugin template.
- **New dependency**: ShedLock (ADR-0125).
- **ADRs**: 0122 (CSP), 0123 (sessions end), 0124 (scans).
- **New workflows**: `codeql.yml`, `osv-scanner.yml`.
