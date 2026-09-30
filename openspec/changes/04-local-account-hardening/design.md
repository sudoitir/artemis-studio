# Design: 04-local-account-hardening

## Context
Local accounts today have:
- a password (bcrypt)
- an in-memory throttle keyed by username and source (5 failures, then backoff)
- a 5-minute step-up, used only by plugin admin
- an 8h session timeout

They have no second factor, no password policy (only `@NotBlank`), no account lockout, no per-IP limit, no idle or absolute timeout, and no session visibility. A stolen password is enough to become an administrator.

This change hardens local sign-in and keeps it easy to use. It follows `artemis-studio/openspec/changes/04-local-account-hardening/` (the delta for `identity-and-sessions`).

## Goals / Non-goals
- Goals: a stolen password alone never signs in to an MFA account; sign-in stays one extra step at most, and none on a trusted device; every authentication change is audited.
- Non-goals: SAML, LDAP, SCIM; changing SSO logins; risk-based authentication; MFA challenges on bearer tokens (D7).

## Risks / Trade-offs
- Trust on first use for enrolment (D8): a password thief who signs in before the owner enrols can enrol their own factor. Mitigated by audit with address; accepted in ADR-0142.
- Account lockout is a denial-of-service lever; a trusted device bypasses the lock and admins can unlock; break-glass covers the last admin.
- Changing `public-url`'s host invalidates passkeys; recovery codes are always issued and the setting warns.
- The offline breached list is the top 100k only; the optional online lookup covers the long tail for installations that allow outbound calls.
- The per-IP limit behind a shared NAT can slow legitimate users; limits are generous and only count failures.
- Known accounts do one extra DB write on failure; the small timing difference is accepted (ADR-0143).

## ADRs
- 0142 Second factors for local accounts
- 0143 Password policy, lockout, sign-in limits and trusted proxies
- 0144 Session lifetimes and session management

## Decisions (made; not reopened during implementation)
| # | Decision | Source |
|---|---|---|
| D1 | The built-in **ADMIN role requires MFA by default**. Every other role opts in per role (`role.requires_mfa`), and the flag can be edited on built-in roles too. | user ("follow recommended") |
| D2 | `ARTEMIS_STUDIO_PUBLIC_URL` becomes the Studio-wide `artemis-studio.public-url`. It is the WebAuthn relying party (RP) and the base for alerting links. When it is unset, passkeys show as unavailable along with the setting to add; TOTP still works. | user |
| D3 | **MFA applies only to local-provider sessions.** Any other provider (OIDC today, SAML or LDAP in future) is exempt, because its identity provider owns MFA. The check asks "is this a local session", never "is this one of a list of SSO types". | user |
| D4 | TOTP set-up shows a **QR code** for any authenticator app, plus the secret as text. | user |
| D5 | **Trust this device**: skips the second factor at sign-in for a period the **admin** sets. `0` turns it off. | user |
| D6 | TOTP is RFC 6238 on the JDK `Mac`, tested against the RFC vectors, because there is no maintained Java TOTP library worth the dependency. WebAuthn uses Spring Security 7's `WebAuthnRelyingPartyOperations` (webauthn4j) from our own JSON controllers, with Spring's JDBC repositories. | design |
| D7 | Bearer and MCP calls are not challenged for MFA. A token records whether it was minted from an MFA-verified session, and one minted without MFA stops working once its owner's roles require MFA. | review |
| D8 | First-sign-in enrolment is trust-on-first-use (TOFU), the industry norm. ADR-0142 accepts it; the mitigation is an audited `MFA_ENROL` with the address. Recovery is by break-glass (D10). | review |
| D9 | The idle timeout measures **user activity**, not polling: the UI sets a header on requests the user caused. | review |
| D10 | **Break-glass**: the deploy-time property `artemis-studio.identity-local.recover=<username>` runs at startup. It clears the account's lock, factors and trusted devices, revokes its API tokens, ends its sessions, sets must-change-password, and logs and audits the action. This covers a sole admin who lost both device and recovery codes. | review |
| D11 | Implementation runs **sequentially** (A→B→C→D→E) with `implementer` subagents, not as parallel worktrees, because A–D all touch `LoginService`, `SessionAuthentication` and `LocalIdentity`. One `reviewer` pass before the PR. | review |

**Defaults.** Runtime-configurable ones are Settings entries.

| Setting | Default |
|---|---|
| Password minimum length | 12 |
| Account lock | after 10 consecutive failures, for 15 min |
| Per-IP limit | 30 failures per 10 min |
| Idle timeout | 30 min |
| Absolute session lifetime | 12 h |
| Trusted-device lifetime | 30 d |
| Recovery codes | 10 |

## How it works

### Core rule: session state
- `SessionAuthentication.establish(principal, SessionFacts facts)` takes explicit facts:
  - `authenticatedAt` (step-up freshness)
  - `mfaVerifiedAt` and the method (`TOTP`, `WEBAUTHN`, `RECOVERY_CODE`, `TRUSTED_DEVICE`, or null)
  - `signedInAt`, `clientAddress`, `userAgent`
- It still rotates the session id and reissues the CSRF token.
- A password change re-establishes the session and **carries over** `mfaVerifiedAt`, `signedInAt` and `authenticatedAt`. It no longer refreshes freshness.
- A trusted-device sign-in sets `mfaVerifiedAt` with method `TRUSTED_DEVICE`. It sets `authenticatedAt` so that **step-up is still needed**.
- Only two things set `authenticatedAt = now`: password plus factor, or password alone for an account with no factor.
- `POST /auth/login` first clears any existing `SecurityContext` and all `PENDING_*` attributes.

### A. Password policy (`feature/identitylocal`)
- `PasswordPolicy` runs *before* the encoder. It checks:
  - Minimum length: runtime INT setting `identity-local.password.min-length`, default 12.
  - At most 72 UTF-8 bytes. bcrypt truncates past that and Spring's encoder rejects it.
  - Not equal to the username (case-insensitive).
  - Not in the offline breached list: SecLists top-100k (MIT), shipped as `identitylocal/breached-passwords.txt.gz` and loaded once into a `HashSet`.
- Optional online check, as the spec requires:
  - HIBP k-anonymity through `RestClient`, with `Add-Padding` and a 2 s timeout.
  - Fails open with a warning.
  - Off by default: runtime BOOLEAN setting `identity-local.password.breach-lookup` (`SettingDef.Kind.BOOLEAN`, ADR-0138), so an administrator switches it without a restart.
- Applies to password change and to admin create-user. Break-glass does not need it, because it forces a change.
- A rejection is 400 `password-policy` with a reason, shown inline.
- A password change also:
  - ends the user's *other* sessions, through a new `SessionTerminator.endSessionsOfExcept(username, currentId)` run after commit
  - revokes all of the user's trusted devices
  - is throttled under the login limiter key

### B. Lockout and sign-in limits (`kernel/security`)
- **Trusted proxies first.**
  - Set `server.forward-headers-strategy: native` with `server.tomcat.remoteip.internal-proxies` (default: private ranges), and document it.
  - Limiter keys, `CLIENT_ADDRESS` and the audit all use the resolved address.
  - A test checks that a forged `X-Forwarded-For` from a non-proxy does not change the key.
- **`LoginAttemptLimiter`** moves to Caffeine with `expireAfterWrite`, which fixes the unbounded map. It holds two keys, and both apply to unknown usernames too:
  - username + source (as today)
  - per IP across accounts: 30 failures per 10 min
  - Usernames are matched exactly (`app_user.username` is unique and looked up without case folding), so the limiter keys on the name as typed and every key an account's own sign-ins use is its exact name. A name in another case is an unknown name: it fills its own key and the per-IP key, and never the account's.
- **Account lock in the DB**, so it holds across instances.
  - Changeset `kernel/security/changes/0006-account-lockout.sql` adds `app_user.locked_until timestamptz` and `failed_login_count int`.
  - `AccountLockout` writes failures in a **`REQUIRES_NEW`** transaction, so the login rollback cannot undo them.
  - The SQL is atomic: `count = count + 1`, and it sets `locked_until` when the count reaches 10.
- **What counts.** A failure is a wrong password **or** a wrong second factor. Success (reset the counter, clear the limiter keys) is recorded **only once the whole login completes**, including the factor.
- **A locked account answers exactly like a wrong password.** Same 401, same body, and no `SECOND_FACTOR_REQUIRED` even when the password is correct.
- **Exception to the lock:** a login that presents a valid trusted-device cookie for that user bypasses the lock. This stops spraying from locking out the well-known `admin` name. The attempt still counts.
- **Timing.** `LocalIdentity` matches unknown usernames against a dummy hash made by the same encoder. The remaining timing difference from the DB write for known accounts is accepted in ADR-0143.
- **Audit.**
  - `loginAttempted` moves before the throttle check.
  - A throttled attempt closes the audit row as `failed("throttled")`.
  - A correct password with a second factor still owed closes the `LOGIN` row as `failed("password accepted, second factor not given")`, and the row becomes a success only when the factor completes the sign-in. A factor that is wrong, never given or expired leaves it failed.
  - `ACCOUNT_LOCK` is recorded when the lock trips.
- **Admin unlock:** `PUT /api/v1/users/{id}/unlock` (`user:admin`).
  - Clears the DB lock and the limiter keys.
  - Audits `ACCOUNT_UNLOCK` with actor and target.
  - The UI shows a "Locked until …" badge and an Unlock action in `UsersPanel`.

### C. Session lifetimes and management (`kernel/security`, UI in `web/src/features/security`)
- **Settings** (runtime DURATION):
  - `security.session.idle-timeout`, default 30m
  - `security.session.absolute-lifetime`, default 12h
- **`SessionLifetimeFilter`** ends the session with a 401 when `now - signedInAt > absolute` or `now - LAST_ACTIVITY_AT > idle`.
- **What counts as activity:**
  - `LAST_ACTIVITY_AT` is updated only by mutating requests and by requests carrying `X-Studio-Activity: 1`.
  - `web/src/kernel/api/request.ts` adds that header when the user has used the pointer or keyboard in the last 60 s. One global listener tracks this.
  - As a result, polling and SSE do not keep an unattended tab alive.
- **Event streams follow a rotated session.** A step-up, enrolling a factor and a password change give the session a new id; `SessionAuthentication` publishes `SessionIdChanged(oldId, newId)` and `SseHub` re-keys the session's streams, so they are not closed as the ended session they are not. A new sign-in publishes nothing, so a stream never passes to the next user of a browser.
- Spring Session's `maxInactiveInterval` is set to the idle timeout as a storage-level backstop.
- **Session handles** are `hex(sha256(sessionId))[0..32]`. The raw id is never exposed.
- **Endpoints**, all using `FindByIndexNameSessionRepository.findByPrincipalName`:
  - `GET /api/v1/auth/sessions`: start, last activity, address, client, `current`
  - `DELETE /api/v1/auth/sessions/{handle}`
  - admin: `GET` and `DELETE /api/v1/users/{id}/sessions[/{handle}]` (`user:admin`)
- **Audit:** `SESSION_END` (actor, target).
- **UI:** a "Sessions" section on the account page, and a Sessions drawer from each `UsersPanel` row.

### D. Second factors (`feature/identitylocal/mfa`, kernel SPI in `kernel/security`)
- **SPI `SecondFactors`** in `kernel.security`: `enrolled(userId)`, `required(userId)`, `options(userId)`, `verify(userId, proof)`.
  - `required` means any held role has `requires_mfa` and the user is local (D3).
  - Implemented in identity-local, so when local accounts are off there is no bean.
- **Login flow:**
  1. The password is correct and the account is not locked (or a trusted device applies).
  2. A valid trusted-device cookie → `establish` with `TRUSTED_DEVICE`.
  3. Otherwise, if enrolled → rotate the session id and store `PENDING_SECOND_FACTOR{userId, at}` with **no principal**. The response is `200 {status:"SECOND_FACTOR_REQUIRED", methods, trustDeviceDays}`.
  4. `POST /auth/second-factor/options` returns WebAuthn request options and stores the challenge in the session.
  5. `POST /auth/second-factor {totpCode | webauthn | recoveryCode, trustDevice}` verifies, records a lockout failure or success, and establishes the session.
  - The pending state expires after 5 min.
  - `/auth/second-factor` acts as **login** only when there is no principal. It acts as **step-up** only when a principal exists whose `userId` equals `PENDING_STEP_UP.userId`.
  - If a factor is required but none is enrolled, the session is established with the flag `secondFactorEnrolmentRequired`.
- **`RestrictedSessionFilter`** replaces `MustChangePasswordFilter`.
  - It checks must-change-password **first** (423 `must-change-password`), then enrolment (423 `mfa-enrolment-required`).
  - Allow-list, exact paths:
    - `POST /auth/mfa/totp`, `POST /auth/mfa/totp/confirm`
    - `POST /auth/mfa/webauthn/options`, `POST /auth/mfa/webauthn`
    - `/auth/me`, `/auth/logout`
    - the password endpoint, while must-change-password is set
  - Finishing enrolment re-establishes the full session with `mfaVerifiedAt`.
- **Tokens (D7):**
  - A changeset adds `api_token.minted_with_mfa boolean`.
  - `mint` requires a session caller (no minting from a bearer token) and sets the flag from `mfaVerifiedAt`. This is the one "keys cannot manage keys" rule (ADR-0136): `requireSession` guards every key endpoint with `session-required`.
  - `ApiTokenService.authenticate` rejects a token when the owner is `required()` and the token is `!minted_with_mfa`.
  - `MFA_RESET` revokes the target's tokens.
  - A session without a completed factor, for a user who requires one, cannot mint. The filter blocks it and `mint` checks as well.
- **TOTP:**
  - HmacSHA1, 6 digits, 30 s steps, ±1 step.
  - The secret is sealed with `SecretVault.seal("local_totp:" + userId, …)` (ADR-0132) into a `sealed` column.
  - Tables `local_totp(user_id, sealed, last_step)` for the active secret and `local_totp_pending(user_id, sealed)`, both registered as `SealedStore`s so a key rotation re-wraps them. Enrolling writes the new secret as **pending**, which never overwrites the active one until confirmed.
  - Replay is blocked atomically: `UPDATE … SET last_step=:s WHERE user_id=:u AND last_step < :s` must update exactly one row.
  - `POST /auth/mfa/totp` returns the secret and the `otpauth://totp/<issuer>:<username>?secret&issuer` URI (issuer from `Branding`). The UI renders the URI as a **QR code** and shows the secret, grouped, with a copy button.
  - `POST /auth/mfa/totp/confirm {code}` activates it.
- **WebAuthn and passkeys:**
  - Dependency `spring-security-webauthn` (exact artifact checked with ctx7).
  - The RP comes from `public-url` (D2).
  - The user entity's `name` is the user **UUID**, so renaming a user does not orphan credentials.
  - Spring's `user_entities` and `user_credentials` tables are created by Liquibase.
  - A user can have several passkeys, each labelled.
  - The `public-url` setting's description warns that changing the host invalidates passkeys.
  - When `public-url` is unset, `GET /auth/mfa` reports `webauthn:{available:false, reason}`.
- **Recovery codes:**
  - 10 codes of 10 base32 characters, stored as an HMAC-SHA256, because 50 bits fall to an offline guess as a plain hash. The key is 32 random bytes made once (`local_recovery_key`), sealed by `SecretVault` and registered as a `SealedStore`, so a key rotation re-wraps it and never changes it (a key derived from a key-encryption key would change and orphan every hash).
  - **Issued at the first enrolment of any factor**, TOTP or passkey.
  - Input ignores case and dashes.
  - Use is atomic: `UPDATE … SET used_at=now() WHERE hash=:h AND used_at IS NULL`. Each use is audited `RECOVERY_CODE_USE`.
  - Regenerating them needs step-up.
- **Changing factors:**
  - **Adding or replacing a factor needs step-up** when the user already has one. Removing a factor needs step-up.
  - Removing the **last** factor while `required()` → 409 `last-factor-required` ("Add another first").
- **Step-up:**
  - `POST /auth/reauthenticate {password}`: if the user is enrolled, it returns `{status:"SECOND_FACTOR_REQUIRED"}` and sets `PENDING_STEP_UP`. A password alone never refreshes freshness for such an account.
  - A trusted device never satisfies step-up.
  - SSO step-up stays the OIDC `max_age` flow.
- **Admin reset:** `DELETE /api/v1/users/{id}/second-factors`.
  - Needs `user:admin` plus step-up.
  - If the target is `required()`, the actor's own session must carry `mfaVerifiedAt`.
  - Refused for oneself: 409 `self-reset`, which points to recovery codes.
  - Removes the factors and codes, revokes trusted devices and tokens, ends sessions, and audits `MFA_RESET`.
- **Roles:**
  - A changeset adds `role.requires_mfa boolean not null default false` and sets it true for `ADMIN` (D1).
  - `RoleService` allows only this field to change on built-in roles. Its update already ends members' sessions.
  - `UserService.addGrant` **also ends the user's sessions when the granted role requires MFA**, so the requirement takes effect at once.
- **Trusted devices (D5):**
  - Table `local_trusted_device(expires_at, created_at, last_used_at, user_agent, client_address, token_hash, user_id, id)`, with the uuids last as non-negotiable 7 has it and `user_id` cascading from `app_user`.
  - The cookie `as_trusted_device` holds a 32-byte random token. It is HttpOnly, Secure, `SameSite=Strict`, with path `/api/v1/auth`.
  - Lookup is by user: every one of the user's rows is compared with the presented token's SHA-256 in constant time.
  - Runtime DURATION setting `identity-local.mfa.trusted-device-lifetime`, default 30d. The effective expiry is `min(expires_at, created_at + current setting)`.
  - Setting it to `0` turns the feature off: the checkbox is disabled with a reason, and cookies are ignored and cleared.
  - Revoked on password change, factor reset, disable, break-glass, or removal of all factors. The user can also revoke devices from the account page.
  - Audited `TRUSTED_DEVICE_ADD` and `TRUSTED_DEVICE_REVOKE`.
- **Break-glass (D10):** `feature/identitylocal/AccountRecovery` runs on `ApplicationReadyEvent` when the property is set. It logs a warning to remove the property.
- **Audit event names:** `MFA_ENROL` (with address), `MFA_REMOVE`, `MFA_RESET`, `RECOVERY_CODE_USE`, `SECOND_FACTOR_FAILED`, `TRUSTED_DEVICE_ADD`, `TRUSTED_DEVICE_REVOKE`, `ACCOUNT_RECOVER`.
- **Tables** go in `db/changelog/feature/identitylocal/`, a new module changelog included from the master, with column order per non-negotiable 7.

### E. Frontend
- **Move step-up into the kernel.** `StepUp`, `freshSignIn`, `needsReauthentication` and `useReauthenticate` move to `web/src/kernel/auth/`, and plugins import them from there. `StepUp` gains the second-factor step.
- **`kernel/auth/SecondFactorForm`:**
  - 6-digit code field (`autocomplete="one-time-code"`)
  - "Use a passkey", using native `PublicKeyCredential.parse*OptionsFromJSON` and `toJSON()`, with no WebAuthn library
  - "Use a recovery code"
  - "Trust this device for N days" checkbox, shown at login only
- **`LoginView`** gets a second step.
- **Enrolment route.** `/enrol-second-factor` is added to `PUBLIC_PATHS`, and `RootLayout` routes there when the flag is set.
- **Enrolment screen:**
  - "Scan with your authenticator app": the QR code (one small QR library, chosen with ctx7), the text secret with copy, and a code to confirm.
  - Or "Add a passkey".
  - Then a recovery-codes dialog, shown once, with copy and download, and an "I saved them" button to continue.
- **Account sections:**
  - "Two-step verification" (identity-local): factors, passkeys, trusted devices, regenerate codes
  - "Sessions" (security)
- **Admin (`features/security`):**
  - `UsersPanel`: Locked badge with Unlock, Sessions drawer, and "Reset two-step verification" (`ConfirmByTyping` plus step-up).
  - `RolesPanel`: a "Require two-step verification" switch, with the hint "Applies to local accounts; single sign-on users rely on their identity provider's MFA."
- Every error states its cause and the next action. DTOs come from `npm run gen:api`.

### F. Scripts (ADMIN now needs a factor)
- `scripts/demo-seed.sh` enrols TOTP through the API after the password change and exports `ADMIN_TOTP_SECRET`.
- `web/scripts/session.ts` `signIn` fills in the code using a shared `totp.ts` of about 15 lines on node `crypto`.
- The `justfile` passes the variable through.
- Backend ITs sign in with fixture accounts whose roles do not require MFA, and dedicated MFA ITs cover the rest.

