# ADR-0136: Tokens rotate with an overlap, live under a live cap, are rate limited and counted per hour

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Mahdi Amirabdollahi

## Context

API tokens (ADR-0039) are how scripts, MCP clients and the command line reach Studio. They had an
optional expiry, no rotation, no limit on how hard one can hit Studio, and no record of use beyond
`last_used_at`. ADR-0046 listed "rotation is manual, no expiry policy" as a known cost. Only a
token's owner could see or revoke it, so a leaked token of someone on holiday stayed live.

## Decision

**Rotation keeps the replaced secret in three columns.** `api_token` gains `previous_prefix`,
`previous_token_hash` and `previous_valid_until`. Rotating moves the current prefix and hash there,
valid for `apitokens.rotation-overlap` (24 hours), and writes a new pair. Lookup is by `prefix` or,
failing that, `previous_prefix`. Grants, the MCP allow-list, `created_at` and `expires_at` stay, so
rotation never extends a lifetime. The previous pair is kept after its window, so a late use of it
is recognised: 401, and a `TOKEN_REJECTED` audit row under the owner and token, at most one a minute
per token. A revoked or expired token cannot be rotated, and rotating again ends the previous overlap.

**The lifetime cap is evaluated live.** Effective expiry is `min(expires_at, created_at + cap)`,
computed at authentication and in every view, with `apitokens.max-lifetime` defaulting to 90 days.
Lowering the cap shortens every token at once and needs no sweep. `expires_at` becomes required,
and minting beyond `now + cap` is refused with the latest allowed expiry.

**Limits are a servlet filter on token requests only.** `TokenRequestLimitFilter` runs after the
security chain and acts only on a `TokenPrincipal`, the principal subclass that carries the token's
id. The limits are a fixed one-minute window per token (600) and per user across their tokens
(1200), plus an in-flight cap per token (8). Every token response states `RateLimit-Limit`,
`RateLimit-Remaining` and `RateLimit-Reset`; a refusal is 429 with `Retry-After` and a
`rate-limited` problem. Sessions are never limited: the console polls.

**Usage is hourly counters.** `api_token_usage(token_id, hour, requests, denied, limited, errors)`
is filled from an in-memory buffer and flushed with `last_used_at` by the existing minute job, as
one upsert per bucket. It is a data-lifecycle store (ADR-0134), kept 90 days by default and at
least 30.

**Administrators see every token.** `/api/v1/admin/tokens`, behind the global `token:admin`, lists
metadata (owner, grants, tools, effective expiry, last use, a stale flag after
`apitokens.stale-after`, 30 days), revokes any token and reads its usage. Minting and rotation stay
with the owner.

`StudioPrincipal` (plugin API) is unchanged. The token's id and allow-list live on
`TokenPrincipal` in `kernel.security`, so the plugin contract does not break.

## Consequences

- A leaked secret can be replaced without downtime, and a leaked token can be stopped by an
  administrator.
- **Breaking:** tokens minted without an expiry get `created_at + 90 days` on upgrade, so older
  ones stop working at once. Minting requires `expiresAt`.
- Limit windows and the usage buffer are in memory. A restart resets windows and can lose up to a
  minute of counts, and a fixed window allows up to twice the rate across a minute boundary.
  Acceptable on one instance, as ADR-0039 accepts it for `last_used_at`. It is revisited with
  multi-instance HA.
- An MCP call denied by the gate (ADR-0137) is counted as denied through a request attribute,
  because its HTTP status is 200.

## Alternatives considered

- **Clamping stored expiries when the cap changes.** A second write path that races with minting,
  for the same result as computing it live.
- **A child table of secrets per token.** More joins for a case that needs exactly two secrets.
- **Bucket4j.** A new dependency for a fixed window on one instance. It is the upgrade path, with
  a shared store, if Studio runs clustered.
- **One audit row per token request.** It would flood `audit_event`. The spec asks for a summary,
  and counters give exactly that.
