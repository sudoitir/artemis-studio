# ADR-0144: Password policy, account lockout and sign-in limits

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Mahdi Amirabdollahi

## Context

Passwords only had to be non-blank. The failed-login throttle was an unbounded in-memory
map keyed by username and source, trusted `X-Forwarded-For` from any client, and a correct
password reset it before any later check.

## Decision

- **Policy** (`PasswordPolicy`, identity-local): minimum length from the runtime setting
  `identity-local.password.min-length` (default 12), at most 72 UTF-8 bytes (bcrypt's limit),
  not the username, and not in an offline list of the 100,000 most common passwords
  (SecLists, MIT) shipped in the jar. An optional HIBP k-anonymity lookup,
  switched by the runtime BOOLEAN setting `identity-local.password.breach-lookup` (ADR-0138,
  off by default), sends only a 5-character hash prefix and fails open.
- **Client address**: `server.forward-headers-strategy: native` with Tomcat's
  `internal-proxies`; only trusted proxies may set the forwarded address.
- **Throttle**: Caffeine-backed, per username and source and per source across accounts,
  counting unknown usernames too.
- **Lockout**: `app_user.failed_login_count` and `locked_until`, written in their own
  transaction with atomic SQL so a failed login's rollback cannot undo them. 10 consecutive
  failures lock for 15 minutes. Wrong passwords and wrong second factors both count; success
  is recorded only after the whole sign-in. A locked account answers exactly as a wrong
  password. A valid trusted device bypasses the lock, so spraying cannot lock the owner out.
- Unknown usernames are checked against a dummy hash. Known accounts do one extra write on
  failure; the small timing difference is accepted.

## Consequences

- Deployments behind a proxy outside the private ranges set `server.tomcat.remoteip.internal-proxies`.
- An attacker can still lock a password-only account for 15 minutes at a time; the owner
  uses a trusted device or an administrator unlocks.
- The offline list catches common passwords, not every breached one.

## Alternatives considered

- **Bucket4j or a database-backed rate limiter**: the lock lives in the database already;
  the throttle is a courtesy layer and per-instance is enough.
- **Permanent lockout**: turns every attacker into a denial of service.
- **Composition rules** (digits, symbols): NIST SP 800-63B advises length and breach checks
  instead.
