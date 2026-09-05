# ADR-0039: API tokens — SHA-256 hash, prefix lookup, grants intersected with the live owner

- **Status**: accepted
- **Date**: 2026-09-05
- **Deciders**: Mahdi Amirabdollahi

## Context

Session cookies (ADR-0037) don't serve scripted, non-browser callers —
CI jobs, cron scripts, external automation. Phase 8 needs a personal,
revocable credential narrowable to a subset of its owner's grants (a
decision already settled with the user: "owned by a user, narrowable to a
subset of that user's grants").

## Decision

A minted token is `as_<11-char base64url prefix>_<43-char base64url secret>`
— 8 bytes of prefix entropy (indexed, plaintext, used only for lookup) and 32
bytes of secret entropy (never stored in plaintext). The `_` separator is
found by a **fixed prefix length**, not by splitting on `_`: base64url's
alphabet includes `_`, so searching for a separator character would be
ambiguous.

Storage: `SHA-256` of the secret, not bcrypt. The secret already carries 256
bits of generated entropy — a slow KDF defends a low-entropy human password
against brute force, and buys nothing here while costing real latency
(bcrypt is ~100ms by design) on every API-token-authenticated request.
Lookup is by the indexed plaintext prefix; the hash comparison uses
`MessageDigest.isEqual` for constant-time comparison.

A token's configured grants are **intersected**, not copied, with its
owner's live grants at authentication time (`ApiTokenService.authenticate`),
so demoting or disabling the owning user immediately narrows or disables the
token — no separate revocation sweep needed. `last_used_at` is updated at
most once a minute per token, via a dirty in-memory map flushed on a
schedule, so a busy caller doesn't write on every request — the same
"batch, don't write per event" instinct as ADR-0028's `broker_event`
insertion.

`ApiTokenAuthenticationFilter` is registered
**`addFilterAfter(SecurityContextHolderFilter.class)`**, not before.

## Consequences

- Revoking a token, or disabling/demoting its owner, takes effect on the
  very next request — no cache to invalidate, no sweep to wait for.
- **A real bug, found in manual verification and fixed before merge**: the
  filter was initially registered `addFilterBefore(SecurityContextHolderFilter)`.
  `SecurityContextHolderFilter` loads a (session-less, therefore empty)
  context from the repository and calls `SecurityContextHolder.setContext(...)`
  unconditionally — running immediately after the token filter, it silently
  overwrote every bearer-token authentication with that empty context. Every
  API-token request was authenticating successfully inside the filter and
  then being discarded one filter later, always falling back to `401`. Fixed
  to `addFilterAfter`; `ApiTokenAuthenticationFilterTest` now runs against
  the real `SecurityFilterChain` (not a unit test on the filter alone,
  which would not have caught this) and is confirmed to fail on the old
  ordering.
- A `TokenGrantRequest` with a `null` `scopeId` (the natural shape for a
  `GLOBAL` grant, matching `UserService.addGrant`'s own convention) initially
  violated `api_token_grant`'s `NOT NULL scope_id` column instead of
  defaulting to `ScopeIds.GLOBAL` — also found in manual verification and
  fixed in `TokensController.create`, matching the existing `UserService`
  pattern. A missing/blank grant `action` threw an unhandled `NullPointerException`
  (`500`) instead of a `400` — fixed with `@NotBlank`/`@NotEmpty`/`@Valid`
  validation on the nested DTO.
- Single-instance only: the last-used buffer is in-memory, lost on restart.
  Acceptable ceiling for now, revisited at v1.0's multi-instance HA item.

## Alternatives considered

- **bcrypt/argon2 for the token secret**, matching password hashing.
  Rejected: the secret has no human-guessable structure to defend against;
  the KDF's cost buys nothing and would be paid on every request.
- **Underscore-delimited prefix/secret split.** Rejected: base64url's
  alphabet includes `_`, making a scan-for-separator approach ambiguous;
  a fixed prefix length is unambiguous and simpler.
- **Copying a token's grants at mint time rather than intersecting live.**
  Rejected: would require a separate revocation sweep whenever an owner is
  demoted or disabled, adding a second code path with its own race window.
