## ADDED Requirements

### Requirement: A local account can enrol TOTP and WebAuthn second factors
A user SHALL be able to enrol a TOTP authenticator and one or more WebAuthn or passkey credentials, and SHALL receive single-use recovery codes at enrolment.

#### Scenario: Enrol TOTP
- **WHEN** a user confirms a valid code
- **THEN** the factor is active and recovery codes are shown once

#### Scenario: Recovery code used
- **WHEN** a user signs in with a recovery code
- **THEN** that code cannot be used again and the use is audited

### Requirement: An administrator can require MFA per role
Studio SHALL let an administrator mark a role as requiring MFA, and a user holding such a role SHALL not reach the console until a second factor is enrolled and verified.

#### Scenario: Required, not enrolled
- **WHEN** a user with a required role signs in without a factor
- **THEN** they are taken to enrolment and reach nothing else

#### Scenario: Password stolen
- **WHEN** someone has only the password of an MFA account
- **THEN** sign-in does not complete

#### Scenario: A token minted around MFA
- **WHEN** a user whose role requires MFA tries to mint an API token from a session that has not completed the second factor
- **THEN** minting is refused

### Requirement: Step-up re-authentication uses the second factor
When an account has a second factor, step-up re-authentication for a sensitive action SHALL require it as well as the password.

#### Scenario: Step-up with a password only
- **WHEN** a user with an enrolled factor re-enters only their password for a sensitive action
- **THEN** the action is still refused until the factor is verified

### Requirement: An administrator can reset a user's second factors
An administrator with the right permission and fresh authentication SHALL be able to remove a user's factors, which ends that user's sessions and makes them enrol again at next sign-in if their role requires MFA.

#### Scenario: Lost device
- **WHEN** an administrator resets a user's factors
- **THEN** the user's sessions end, the reset is audited, and the next sign-in leads to enrolment

#### Scenario: Resetting one's own factors
- **WHEN** an administrator tries to reset their own factors through the administration surface
- **THEN** it is refused, and they use their own recovery codes instead

### Requirement: Passwords meet a policy
Studio SHALL enforce a minimum length and reject passwords found in the breached-password list; the online k-anonymity lookup SHALL be optional and off by default.

#### Scenario: Breached password
- **WHEN** a user sets a password on the list
- **THEN** it is rejected with a reason

#### Scenario: Offline
- **WHEN** the online lookup is off
- **THEN** the offline list alone is used and no password data leaves Studio

### Requirement: Repeated failures lock and slow down sign-in
Studio SHALL lock an account after repeated failures, and SHALL extend today's throttle per username and source with limits per IP address across accounts, without revealing whether the account exists.

#### Scenario: Lockout
- **WHEN** failures exceed the limit
- **THEN** further attempts are refused until the lock expires or an administrator unlocks the account

#### Scenario: Spraying
- **WHEN** one address tries many accounts
- **THEN** the per-IP limit applies and responses are identical for unknown and known accounts

### Requirement: Sessions expire when idle and after an absolute lifetime
Studio SHALL end a session after a configurable idle period and after a configurable absolute lifetime, whichever comes first.

#### Scenario: Idle session
- **WHEN** a session is not used for longer than the idle period
- **THEN** its next request is unauthenticated

#### Scenario: Long-lived session
- **WHEN** a session stays active past the absolute lifetime
- **THEN** its next request is unauthenticated and the user signs in again

### Requirement: Users manage their own sessions
A user SHALL see their active sessions (start, last use, address, client) and end any of them.

#### Scenario: End another session
- **WHEN** a user ends a session
- **THEN** that session is rejected at its next request

### Requirement: Administrators manage everyone's sessions
An administrator with the right permission SHALL list and end any user's sessions.

#### Scenario: Without permission
- **WHEN** a caller lacks the permission
- **THEN** the list and the end action are refused

### Requirement: Authentication changes are audited
Enrolment, removal of a factor, recovery-code use, lockout, unlock and session ends SHALL each produce an audit event naming the actor and the target.

#### Scenario: Factor removed
- **WHEN** a factor is removed
- **THEN** an audit event records who removed it and from which account
