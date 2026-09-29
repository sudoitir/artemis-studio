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

### Requirement: Passwords meet a policy
Studio SHALL enforce a minimum length and reject passwords found in the breached-password list; the online k-anonymity lookup SHALL be optional and off by default.

#### Scenario: Breached password
- **WHEN** a user sets a password on the list
- **THEN** it is rejected with a reason

#### Scenario: Offline
- **WHEN** the online lookup is off
- **THEN** the offline list alone is used and no password data leaves Studio

### Requirement: Repeated failures lock and slow down sign-in
Studio SHALL lock an account after repeated failures and SHALL rate-limit login attempts per IP address and per account, without revealing whether the account exists.

#### Scenario: Lockout
- **WHEN** failures exceed the limit
- **THEN** further attempts are refused until the lock expires or an administrator unlocks the account

#### Scenario: Spraying
- **WHEN** one address tries many accounts
- **THEN** the per-IP limit applies and responses are identical for unknown and known accounts

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
