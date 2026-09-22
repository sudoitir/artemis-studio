# ADR-0103: Plugin management belongs to an installer tier and needs fresh authentication

- **Status**: accepted
- **Date**: 2026-09-22
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/plugins`
- **Builds on**: [ADR-0037](0037-session-cookie-authentication.md), [ADR-0038](0038-dynamic-permissions-and-scope-walk.md), [ADR-0039](0039-api-tokens-sha256-intersected-grants.md)

## Context

Installing a plugin runs code on the server, and install is on by default. Role permissions are free-form strings that any `user:admin` can put into a role they then assign to themselves. Grants are loaded once at sign-in and live in an 8-hour session. Local login did not rotate the session id.

## Decision

- **An installer tier,** held in `plugin_installer` and checked on every request. It is **not a permission**, so no role and no wildcard confers it.
  - Its first member is the bootstrapped administrator, or the users named in `artemis-studio.plugins.initial-installers`.
  - Only an installer can change the tier, with fresh authentication, and every change is audited.
  - The server tells the client `canInstall`.
- **Step-up re-authentication** (≤ 5 minutes) is required for every plugin lifecycle action except upload.
  - **Local users** re-enter their password through the login throttle. After 5 failures the session ends.
  - **OIDC users** go through `prompt=login` and `max_age=300`. The returning identity must have the same registration and subject, and an `auth_time` inside the window; the step-up fails when `auth_time` is missing.
- **The session id rotates** on every sign-in and every step-up.
- **API-token and MCP callers cannot install plugins.** Uploads are raw request bodies, so nothing is parsed before authentication.
- **Every lifecycle action is audited and also written to stdout,** because plugins share the database role.

## Consequences

- Stealing a session cookie is not enough to install code. A compromised admin account alone cannot escalate to installing.
- Operators manage one more list. A deployment that has no local bootstrap admin must configure `initial-installers`.
- Some identity providers omit `auth_time`. Step-up then fails for their users, with a stated reason.

## Alternatives considered

- **A `plugin:install` permission.** Any `user:admin` can escalate to it, and a revocation takes up to 8 hours to reach an existing session.
- **A deploy-time allowlist only.** The user wanted installation on by default and managed from the UI.
- **No step-up.** A stolen cookie would be enough to run code on the server.
