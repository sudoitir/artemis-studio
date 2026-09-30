# ADR-0144: Session lifetimes and session management

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Mahdi Amirabdollahi

## Context

Sessions lived in JDBC (ADR-0123) with one 8-hour inactivity timeout. The console polls and
holds an SSE stream, so a tab left open never went idle. Users could not see where they
were signed in.

## Decision

- Two runtime settings: `security.session.idle-timeout` (30 min) and
  `security.session.absolute-lifetime` (12 h). `SessionLifetimeFilter` ends a session past
  either.
- **Idle means no user activity.** The session's `LAST_ACTIVITY_AT` moves only on mutating
  requests and on requests with `X-Studio-Activity: 1`, which the UI adds when the user used
  the pointer or keyboard in the last minute. Spring Session's `maxInactiveInterval` is the
  storage backstop.
- `SessionAuthentication.establish` takes explicit facts (signed in, authenticated, second
  factor, address, client) so each path states what it proves.
- Users list and end their own sessions; administrators with `user:admin` list and end
  anyone's. Sessions are identified by a truncated SHA-256 of the session id, never the id.
- A password change ends the user's other sessions.

## Consequences

- An unattended console signs out after 30 minutes.
- A custom client that wants to stay signed in sends the activity header or makes changes.

## Alternatives considered

- **Treat every request as activity**: polling would keep sessions alive forever.
- **Client-side idle logout only**: a closed laptop's tab would never send the logout.
