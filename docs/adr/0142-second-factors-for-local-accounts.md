# ADR-0142: Second factors for local accounts

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Mahdi Amirabdollahi

## Context

A local account's password was the only thing between an attacker and an administrator.
Installations without single sign-on need a second factor, but it has to stay easy: one
extra step at sign-in at most, and a way back when a device is lost. Sign-in is a custom
JSON flow (`LoginService`, ADR-0073), not Spring's form login. External identity providers
already own their users' authentication, including MFA.

## Decision

- Local accounts can enrol **TOTP** (shown as a QR code for any authenticator app) and any
  number of **WebAuthn passkeys**, and get 10 single-use **recovery codes** at the first
  enrolment.
- TOTP is RFC 6238 on the JDK `Mac` (SHA-1, 6 digits, 30 s, ±1 step), tested against the
  RFC vectors. The secret is sealed with `SecretVault` under the AAD `local_totp:<userId>`
  into the `sealed` column of `local_totp` (the active secret) or `local_totp_pending` (one
  awaiting its first code). Both tables are `SealedStore`s, so an online key rotation
  (ADR-0132) re-wraps the secrets and retiring an old key version cannot strand one.
  Replays are refused with an atomic `last_step` update; recovery codes are spent with an
  atomic `used_at` update.
- Recovery codes are kept as an HMAC-SHA256, because a code has 50 bits and a plain hash
  falls to an offline guess. The HMAC key is 32 random bytes made once per installation and
  kept sealed by `SecretVault` in `local_recovery_key`, also a `SealedStore`. A key derived
  from a key-encryption key would change when a version is rotated or retired and orphan
  every stored hash, whereas a sealed key is only re-wrapped by a rotation and never changes.
- WebAuthn uses Spring Security's `WebAuthnRelyingPartyOperations` (webauthn4j) and its JDBC
  repositories, called from our own JSON endpoints. The relying party is
  `artemis-studio.public-url`; without it passkeys are reported unavailable.
- A role carries `requires_mfa`. The built-in `ADMIN` role requires it by default. The rule
  applies **only to sessions of the local provider**: any other provider, present or future,
  is trusted to do its own MFA.
- A correct password for an enrolled account leaves the session unauthenticated with a
  pending second-factor state; the factor completes sign-in. A required but unenrolled user
  gets a restricted session that can only enrol (trust on first use, audited with the
  address).
- **Trusted devices**: after the factor a user may trust the browser for a period the
  administrator sets (`identity-local.mfa.trusted-device-lifetime`, 0 disables). The cookie
  holds a random token; only its SHA-256 is stored. A trusted device never satisfies
  step-up.
- **Tokens** record whether they were minted from an MFA-verified session and stop
  authenticating when their owner requires MFA and they were not. Bearer and MCP calls are
  not challenged further. A token is minted from a browser session only (`403
  session-required` otherwise), because one that could mint tokens would carry itself past
  the check. That is the same rule as "keys cannot manage keys" (ADR-0136): one mechanism,
  `TokensController.requireSession`, guards every key endpoint, and minting also reads the
  session's facts.
- **Redaction** (ADR-0133): the credential key terms of `SecretRedactor` gain `totp`, `recovery code`,
  `trusted device` and `webauthn`, so a code, a recovery code or a passkey assertion that reaches a log line,
  an audit parameter or an error detail is masked like a password. A leak test plants each.
- **Lifecycle** (ADR-0134): expired trusted devices are a `ManagedStore` (one day of grace by default). The
  other tables hold configuration bounded by the number of users (`StoreCoverageTest` lists why).
- Adding, replacing or removing a factor needs step-up; a required user cannot remove the
  last one. An administrator can reset another user's factors, which ends sessions and
  revokes trusted devices and tokens, but not their own.
- **Break-glass**: `artemis-studio.identity-local.recover=<username>` at startup clears that
  account's lock, factors and trusted devices, revokes its API tokens, ends its sessions and
  forces a password change.

## Consequences

- A stolen password no longer signs in to an enrolled account.
- The first administrator of a fresh instance changes the password and enrols a factor
  before reaching the console. Scripts that sign in as the administrator compute a TOTP code.
- Changing the public address's host strands passkeys; recovery codes and TOTP remain.
- Trust on first use: a thief who signs in before the owner enrols can enrol their own
  factor. We accept this; the enrolment is audited with its address.
- Older tokens of administrators minted before this change stop working until reminted
  after verifying a factor.

## Alternatives considered

- **A TOTP library** (e.g. `dev.samstevens.totp`): unmaintained; the algorithm is 40 lines
  with published test vectors.
- **Spring Security's WebAuthn and MFA filters** (`@EnableMultiFactorAuthentication`): they
  assume form or filter-driven login and would replace our principal and session setup.
- **MFA for every provider**: duplicates what identity providers already enforce and adds a
  second prompt for single sign-on users.
- **Challenging bearer tokens**: tokens are for machines; binding them to MFA at mint time
  is the meaningful control.
