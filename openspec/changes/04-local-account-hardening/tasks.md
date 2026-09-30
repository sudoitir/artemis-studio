## 1. Design
- [x] 1.1 Brainstorm and investigate; `/opsx:update` adds design.md, sharpens specs, replaces these tasks
- [x] 1.2 ADR-0143 second factors, ADR-0144 password policy and sign-in limits, ADR-0145 session lifetimes

## 2. Session state core
- [x] 2.1 `SessionAuthentication.establish(principal, SessionFacts)`: explicit authenticatedAt, mfaVerifiedAt + method, signedInAt, client address, user agent; password change carries facts over; login clears context and `PENDING_*` first

## 3. Password policy (A)
- [x] 3.1 `PasswordPolicy` (min length setting, 72-byte max, not username, offline top-100k list) applied to password change and admin create-user; 400 `password-policy`
- [x] 3.2 Optional HIBP k-anonymity lookup behind the runtime BOOLEAN setting `identity-local.password.breach-lookup` (off), 2 s timeout, fail open
- [x] 3.3 Password change ends other sessions (`endSessionsOfExcept`) and is throttled

## 4. Lockout and limits (B)
- [x] 4.1 Trusted proxies: `forward-headers-strategy: native` + `internal-proxies`; forged-header test
- [x] 4.2 `LoginAttemptLimiter` on Caffeine with per-username+source and per-IP keys
- [x] 4.3 DB account lock (`REQUIRES_NEW`, atomic SQL), success only after the full sign-in, identical responses, dummy-hash timing, throttled attempts audited, `ACCOUNT_LOCK`
- [x] 4.4 Admin unlock endpoint + `ACCOUNT_UNLOCK`

## 5. Sessions (C)
- [x] 5.1 Idle and absolute settings; `SessionLifetimeFilter` with activity header; `maxInactiveInterval` backstop
- [x] 5.2 Own and admin session list/end endpoints with hashed handles; `SESSION_END`

## 6. Second factors (D)
- [x] 6.1 `SecondFactors` SPI; `role.requires_mfa` (ADMIN true); local-only rule; grant of a required role ends sessions
- [x] 6.2 TOTP (RFC 6238, sealed secret, pending/active, atomic replay guard) and enrolment endpoints
- [x] 6.3 Recovery codes (atomic single use, regenerate with step-up)
- [x] 6.4 WebAuthn via `WebAuthnRelyingPartyOperations`, `artemis-studio.public-url`, JDBC repositories, availability report
- [x] 6.5 Login second step, `RestrictedSessionFilter`, step-up with factor
- [x] 6.6 Trusted devices (cookie, hashed rows, lifetime setting, lock bypass, revocations)
- [x] 6.7 Tokens: `minted_with_mfa`, session-only minting, rejection when required
- [x] 6.8 Factor management (step-up for add/replace/remove, last-factor guard), admin reset, break-glass recovery; audit events

## 7. Frontend (E)
- [x] 7.1 Move step-up to `kernel/auth`; `SecondFactorForm`; `LoginView` second step; activity header in `request.ts`
- [x] 7.2 Enrolment route with QR code, passkey, recovery-codes dialog
- [x] 7.3 Account sections: two-step verification, sessions
- [x] 7.4 Admin: unlock, sessions drawer, reset two-step verification, role MFA switch; password-policy errors

## 8. Scripts (F)
- [x] 8.1 Demo seed enrols TOTP; `signIn` computes the code from `ADMIN_TOTP_SECRET`

## 9. Finish
- [x] 9.1 Reviewer on the full diff; findings fixed
- [ ] 9.2 `just verify` green; UI screenshots light and dark; PR merged on green CI; change archived
