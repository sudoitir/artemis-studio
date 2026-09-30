## How to run this change
This change states **requirements only**. Run it in a fresh Claude session, in number order:

1. `git fetch`, then create a git worktree for this change on a new branch off `origin/main` (`.claude/rules/00-workflow.md`). Work only there, and remove it after the merge.
2. Read this proposal, its specs, the capabilities it names in `openspec/specs/`, and the ADRs they cite.
3. Brainstorm and investigate (`/opsx:explore`, `superpowers:brainstorming`); check libraries with ctx7. Ask the user only what is really theirs to decide.
4. `/opsx:update`: add `design.md`, sharpen the specs (turn ADDED into MODIFIED where a requirement changes an existing one), replace the stub `tasks.md`.
5. `/opsx:apply` with the harness under **Execution**. New decisions get an ADR.
6. Verify: `just verify`; for UI, run Studio and check screenshots (light and dark, empty and error states).
7. PR, merge on green CI, `/opsx:archive`.

## Why
Installations that do not use SSO rely on local accounts, which today have a password, a failed-login throttle per username and source, step-up re-authentication and an account page, but no second factor, no password policy beyond the basics, and no way to see or end sessions. A stolen password is enough to become an administrator. Operators cannot require stronger authentication for powerful roles, and users cannot tell whether someone else is signed in as them.

## What Changes
- TOTP and WebAuthn or passkey second factors for local accounts, with recovery codes.
- An administrator can require MFA per role, and can reset a user's factors when a device is lost.
- Step-up re-authentication asks for the second factor when the account has one.
- Idle and absolute session timeouts.
- A password policy: minimum length, breached-password check (offline list, optional k-anonymity lookup).
- Account lockout and per-IP and per-account login rate limits.
- Users see and end their own sessions; administrators list and end anyone's.
- All of the above is audited.

## Capabilities
### New Capabilities
- none
### Modified Capabilities
- `identity-and-sessions`: MFA, password policy, lockout and session management for local accounts

## Out of scope
- SAML, LDAP or SCIM.
- Changing SSO logins (they keep their identity provider's own factors).
- Risk-based or adaptive authentication.

## Depends on
none

## Execution
**Subagent-driven, plus a reviewer before the PR**: MFA, password policy, rate limiting and session management are separable and can be built in parallel; sign-in is the product's front door, so the diff gets a second look.

## Impact
Security layer, login and account UI, admin UI, persistence, audit.
