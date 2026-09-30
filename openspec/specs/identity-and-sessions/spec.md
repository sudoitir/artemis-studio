# identity-and-sessions Specification

## Purpose

Defines local user accounts, password authentication, session-based login, and
the first-run bootstrap that gives an operator a way into an otherwise-closed
instance.

## Requirements

### Requirement: The API requires authentication

Every API endpoint SHALL require an authenticated principal except the login
endpoint, the health probe, and the served frontend assets. An unauthenticated
request to a protected endpoint SHALL receive a `401` response as a problem
detail document, not an HTML page.

#### Scenario: Anonymous request is rejected

- **WHEN** a request without a session or a valid API token is made to any
  cluster or settings endpoint
- **THEN** the response is `401` with a problem detail body

#### Scenario: Login endpoint is reachable unauthenticated

- **WHEN** an unauthenticated client calls the login endpoint with credentials
- **THEN** the request is processed rather than rejected for lack of a session

### Requirement: A user authenticates with a username and password

The system SHALL authenticate a user by username and password against a named credential identity provider. The local provider is the one whose passwords Studio stores hashed at rest. On success the system SHALL establish a server-side session, identified to the client by an HTTP-only cookie. A login request SHALL name the provider; when it names none, the local provider SHALL be used. The system SHALL expose an endpoint that returns the current principal's identity, roles and grants, and an endpoint that ends the session.

#### Scenario: Successful login establishes a session

- **WHEN** a user submits a correct username and password
- **THEN** a session is created, a session cookie is set, and subsequent requests with that cookie are authenticated as that user

#### Scenario: Wrong password is rejected

- **WHEN** a user submits an incorrect password
- **THEN** authentication fails and no session is created

#### Scenario: A login naming an unconfigured provider is rejected

- **WHEN** a login request names a provider that is not configured on this installation
- **THEN** authentication fails with the same response as a wrong password and no session is created

#### Scenario: Logout ends the session

- **WHEN** an authenticated user calls logout
- **THEN** the session is invalidated and the same cookie no longer authenticates subsequent requests

#### Scenario: Current identity is readable

- **WHEN** an authenticated user requests their own identity
- **THEN** the response includes their username, roles, and effective grants

### Requirement: Repeated failed logins are throttled

The system SHALL limit failed sign-in attempts per username and source address, and per source address across all accounts, rejecting further attempts for a backoff period. A wrong password and a wrong second factor SHALL both count as failures. The source address SHALL be the client address as resolved through the configured trusted proxies only. Each failed or throttled attempt SHALL be recorded in the audit trail. Responses SHALL be identical for unknown and known accounts.

#### Scenario: Lockout after repeated failures

- **WHEN** a caller submits several consecutive wrong passwords for the same username from the same source
- **THEN** further login attempts from that source are rejected until the backoff period elapses, even with the correct password

#### Scenario: A successful login clears the failure count

- **WHEN** a caller completes sign-in, including any second factor, after prior failed attempts
- **THEN** the failure count for that username and source is reset

#### Scenario: A correct password alone does not clear the count

- **WHEN** a caller submits the correct password of an account with a second factor and then a wrong code
- **THEN** the failure count is not reset and the wrong code counts as a failure

#### Scenario: Spraying

- **WHEN** one address tries many accounts
- **THEN** the per-address limit applies and responses are identical for unknown and known accounts

#### Scenario: A forged forwarding header does not evade the limit

- **WHEN** a client that is not a trusted proxy sends a different `X-Forwarded-For` on each attempt
- **THEN** the attempts are counted against its real address

#### Scenario: A throttled attempt is audited

- **WHEN** an attempt is rejected by the throttle
- **THEN** an audit event records it as failed and throttled

### Requirement: A fresh instance bootstraps one administrator

When no user account exists, the system SHALL create a single administrator account on startup with a randomly generated password, disclosed exactly once in the startup log, and SHALL require that password to be changed, and then a second factor to be enrolled, before the account can be used for anything else.

#### Scenario: First boot creates the admin account

- **WHEN** the application starts against a database with no user accounts
- **THEN** exactly one administrator account is created and its generated password is written to the startup log once

#### Scenario: Subsequent boots do not re-bootstrap

- **WHEN** the application starts against a database that already has a user account
- **THEN** no new account is created

#### Scenario: Login is restricted until the password is changed

- **WHEN** the bootstrap administrator logs in with the generated password
- **THEN** every request other than changing the password is rejected until the password has been changed

#### Scenario: Then until a second factor is enrolled

- **WHEN** the bootstrap administrator has changed the password
- **THEN** every request other than enrolling a second factor is rejected until one is enrolled

### Requirement: A user can change their own password

The system SHALL allow an authenticated user to change their own password by supplying their current password, SHALL reject the change if the current password does not match, and SHALL apply the password policy to the new password. A successful change SHALL end the user's other sessions and revoke their trusted devices, keeping the current session.

#### Scenario: Password change succeeds

- **WHEN** a user submits their correct current password and a new password that meets the policy
- **THEN** the password is updated and a subsequent login uses the new password

#### Scenario: Password change rejects a wrong current password

- **WHEN** a user submits an incorrect current password
- **THEN** the password is not changed

#### Scenario: Other sessions end

- **WHEN** a user signed in on two browsers changes their password in one
- **THEN** the other browser's session is rejected at its next request and the current one continues

### Requirement: A user has an account page for their own identity and credentials

The system SHALL provide every authenticated user, regardless of role, a self-service
account surface reachable from the application's user menu. It SHALL show who the user is
signed in as, the source of that identity, and a summary of the grants they hold; and it
SHALL be the single place a user manages their own password and mints, rotates and revokes
their own API keys.

The administration surface SHALL NOT mint or rotate API keys. It SHALL offer holders of the
token administration permission an inventory of every user's keys, showing metadata only,
with revocation and stale flags, so a leaked key can be stopped without its owner.

#### Scenario: Every user reaches their account

- **WHEN** a user with no administrative permission opens the user menu
- **THEN** the account surface is offered and opens

#### Scenario: The account surface shows the caller's own identity and grants

- **WHEN** a user opens their account
- **THEN** their username, the source of their identity, and the grants they hold are shown

#### Scenario: Keys are managed only from the account surface

- **WHEN** a user with the token administration permission opens the key inventory on the administration surface
- **THEN** every user's keys are listed with revoke, and no mint or rotate action is offered

### Requirement: The account surface explains how to connect an agent

The system SHALL show, on the account surface, what a user needs to connect an MCP client:
the endpoint address for the running instance and a copyable client configuration. It SHALL
NEVER display an existing key's value, which is unrecoverable by design; a placeholder SHALL
stand in unless a key has just been minted in the same interaction.

#### Scenario: Connection details are shown without a key

- **WHEN** a user with no freshly-minted key views the connection helper
- **THEN** the endpoint and a copyable configuration are shown with a placeholder in place
  of the key value

#### Scenario: A freshly minted key is offered in context

- **WHEN** a user mints a key and the value is still in hand
- **THEN** the connection configuration may be copied with that value already in place

### Requirement: The login screen offers the installation's configured identity providers

The system SHALL expose, without authentication, the list of configured identity providers. Each entry carries its identifier, its human label, and whether it is a credential provider (username and password) or a redirect provider (browser sign-in elsewhere), plus a redirect provider's start address. The login screen SHALL be built only from this list:
- a username and password form when at least one credential provider exists, with a provider choice when there is more than one;
- one sign-in action per redirect provider.

The list SHALL NOT include providers that are not configured.

#### Scenario: Only local login is configured

- **WHEN** the installation has only the local provider
- **THEN** the login screen shows a username and password form with no provider choice and no sign-in buttons

#### Scenario: A redirect provider adds a sign-in action

- **WHEN** an OpenID Connect provider is configured alongside local login
- **THEN** the login screen shows the username and password form and a sign-in action labelled with that provider's label

#### Scenario: Two credential providers offer a choice

- **WHEN** two credential providers are configured
- **THEN** the login form offers a labelled choice between them, and the login request names the chosen provider

### Requirement: Signing in issues a new session identifier

Every successful sign-in, through any identity provider, and every successful step-up re-authentication SHALL issue a new session identifier, so that a session identifier known before authentication never becomes authenticated.

#### Scenario: A pre-login session identifier is not authenticated

- **WHEN** a client holding a session identifier signs in successfully
- **THEN** the response carries a different session identifier, and the earlier one does not authenticate any request

### Requirement: Sensitive actions can require fresh authentication

The system SHALL record when a session last authenticated. An action that requires fresh authentication SHALL be refused with `403` and problem type `reauthentication-required` when that was more than 5 minutes ago — not `401`, because the caller is still signed in and a client treats `401` as "sign in again". A user of a credential provider SHALL re-authenticate by re-entering their password and, when their local account has a second factor, by verifying it too. A trusted device, a password change and a password alone for an account with a factor SHALL NOT make authentication fresh. Step-up attempts SHALL be throttled like logins, and after 5 consecutive failed attempts the session SHALL be ended.

#### Scenario: Re-entering the password satisfies step-up

- **WHEN** a user without a second factor whose session is an hour old re-enters their correct password and then repeats the action
- **THEN** the action proceeds

#### Scenario: Step-up with a password only

- **WHEN** a user with an enrolled factor re-enters only their password for a sensitive action
- **THEN** the action is still refused until the factor is verified

#### Scenario: A trusted device does not satisfy step-up

- **WHEN** a user signed in on a trusted device without a second factor attempts a sensitive action
- **THEN** they are asked for their password and second factor

#### Scenario: A password change does not refresh step-up

- **WHEN** a user whose authentication is older than 5 minutes changes their password and then attempts a sensitive action
- **THEN** the action is refused with `reauthentication-required`

#### Scenario: Repeated wrong passwords end the session

- **WHEN** five consecutive step-up attempts in one session fail
- **THEN** the session is ended and the user must sign in again

### Requirement: Cross-site requests need a CSRF token unless a bearer token authenticated them

A state-changing request SHALL carry a valid CSRF token unless a valid API bearer token authenticated it. The system SHALL reject any other `Authorization` header with 401.

#### Scenario: A junk Authorization header does not skip CSRF
- **WHEN** a POST carries the session cookie, no CSRF token and `Authorization: Basic x`
- **THEN** it is rejected with 401

#### Scenario: A valid bearer token needs no CSRF token
- **WHEN** a POST carries a valid API token and no CSRF token
- **THEN** it is processed

### Requirement: Revoking access ends sessions

The system SHALL end every session of each affected user when a user is disabled, when a grant is removed from a user, or when a role's permissions change. Sessions SHALL be stored in the database, so they are shared by every instance and can be found by user.

#### Scenario: A disabled user is signed out
- **WHEN** an administrator disables a signed-in user
- **THEN** that user's next request with the old session is unauthenticated

#### Scenario: A role change applies at once
- **WHEN** an administrator removes a permission from a role
- **THEN** every member's existing session ends and their next sign-in carries the new grants

### Requirement: Studio sends a Content-Security-Policy

Every response SHALL carry a Content-Security-Policy that allows scripts only from Studio's origin, forbids plugins and framing, and restricts connections to Studio's origin. Plugin SVG assets SHALL carry `Content-Security-Policy: sandbox`.

#### Scenario: Studio cannot be framed
- **WHEN** a page from another origin frames Studio
- **THEN** the browser refuses, because of `frame-ancestors 'none'`

#### Scenario: A plugin SVG cannot run script
- **WHEN** a plugin's SVG asset is opened directly
- **THEN** the response carries `Content-Security-Policy: sandbox`

### Requirement: A local account can enrol TOTP and WebAuthn second factors

A user of the local provider SHALL be able to enrol a TOTP authenticator, shown as a QR code and as text, and one or more WebAuthn or passkey credentials, and SHALL receive single-use recovery codes, shown once, at the first enrolment. Passkeys SHALL be offered only when Studio's public address is configured; otherwise the surface SHALL say so and name the setting. Adding, replacing or removing a factor while one is already enrolled SHALL require fresh authentication. A new TOTP secret SHALL NOT replace the active one until it is confirmed.

#### Scenario: Enrol TOTP

- **WHEN** a user scans the QR code and confirms a valid code
- **THEN** the factor is active and recovery codes are shown once

#### Scenario: Recovery code used

- **WHEN** a user signs in with a recovery code
- **THEN** that code cannot be used again and the use is audited

#### Scenario: A code cannot be replayed

- **WHEN** the same TOTP code or recovery code is submitted twice, even concurrently
- **THEN** exactly one submission succeeds

#### Scenario: A stolen session cannot add a factor

- **WHEN** a session whose authentication is older than 5 minutes tries to add a factor to an account that already has one
- **THEN** it is refused with `reauthentication-required`

#### Scenario: Passkeys without a public address

- **WHEN** no public address is configured
- **THEN** passkey enrolment is shown as unavailable with the setting to configure, and TOTP remains available

### Requirement: Sign-in with a second factor

When a local account has an enrolled factor, a correct password SHALL NOT complete sign-in; the session SHALL become authenticated only after the factor, a recovery code, or a trusted device is verified. A locked account SHALL answer a correct password exactly as a wrong one.

#### Scenario: Password stolen

- **WHEN** someone has only the password of an MFA account
- **THEN** sign-in does not complete

#### Scenario: Locked account with the right password

- **WHEN** a locked account is signed in to with the correct password and no trusted device
- **THEN** the response is identical to a wrong password and no second-factor step is offered

### Requirement: An administrator can require MFA per role

Studio SHALL let an administrator mark any role, built-in or custom, as requiring MFA; the built-in administrator role SHALL require it by default. A local user holding such a role SHALL not reach the console until a second factor is enrolled and verified. Users of other identity providers SHALL NOT be asked for Studio's second factor, since their provider owns authentication. Granting a user a role that requires MFA SHALL end their sessions. An API token minted without a verified second factor SHALL stop authenticating once its owner requires MFA.

#### Scenario: Required, not enrolled

- **WHEN** a user with a required role signs in without a factor
- **THEN** they are taken to enrolment and reach nothing else

#### Scenario: First enrolment is trust on first use

- **WHEN** a user with a required role and no factor signs in with the correct password
- **THEN** that session may enrol a factor, and the enrolment is audited with the source address

#### Scenario: A token minted around MFA

- **WHEN** a user whose role requires MFA tries to mint an API token from a session that has not completed the second factor
- **THEN** minting is refused

#### Scenario: An older token after MFA becomes required

- **WHEN** a role starts requiring MFA and a member's token was minted without a verified factor
- **THEN** that token no longer authenticates

#### Scenario: Granting a required role

- **WHEN** an administrator grants a signed-in user a role that requires MFA
- **THEN** the user's sessions end and their next sign-in enforces the requirement

#### Scenario: Single sign-on users are not challenged

- **WHEN** a user signed in through an external identity provider holds a role that requires MFA
- **THEN** they reach the console and can mint tokens without Studio's second factor

#### Scenario: The last factor of a required user

- **WHEN** a user whose role requires MFA removes their only factor
- **THEN** it is refused until another factor is added

### Requirement: A user can trust a device for a period the administrator sets

After verifying a second factor at sign-in, a user SHALL be able to trust the device, so that later sign-ins on it need only the password until the trust expires. The administrator SHALL set the trust period; a period of zero SHALL disable trusted devices. Users SHALL see and revoke their trusted devices. A password change, a factor reset, disabling the account, and removing all factors SHALL revoke them. A trusted device SHALL let its user sign in while the account is locked by failures elsewhere.

#### Scenario: A trusted device skips the factor

- **WHEN** a user who trusted this device signs in with the correct password within the period
- **THEN** sign-in completes without a second factor

#### Scenario: Trust disabled

- **WHEN** the administrator sets the period to zero
- **THEN** the option is not offered and existing trusted devices are ignored

#### Scenario: Reset revokes trust

- **WHEN** an administrator resets a user's factors
- **THEN** the user's trusted devices no longer skip the factor

#### Scenario: Owner signs in during a spray

- **WHEN** an account is locked by failures from elsewhere and its owner signs in on a trusted device with the correct password
- **THEN** sign-in completes

### Requirement: An administrator can reset a user's second factors

An administrator with the right permission and fresh authentication SHALL be able to remove a user's factors, which ends that user's sessions, revokes their trusted devices and API tokens, and makes them enrol again at next sign-in if their role requires MFA. When the target requires MFA, the administrator's own session SHALL have verified a second factor.

#### Scenario: Lost device

- **WHEN** an administrator resets a user's factors
- **THEN** the user's sessions and tokens end, the reset is audited, and the next sign-in leads to enrolment

#### Scenario: Resetting one's own factors

- **WHEN** an administrator tries to reset their own factors through the administration surface
- **THEN** it is refused, and they use their own recovery codes instead

### Requirement: An operator can recover a local account at startup

Studio SHALL let an operator with access to the deployment name one local account to recover at startup; it SHALL clear that account's lock, factors and trusted devices, revoke its API tokens, require a password change, and log and audit the recovery.

#### Scenario: Sole administrator lost device and codes

- **WHEN** the operator restarts Studio naming the administrator for recovery
- **THEN** the administrator can sign in with their password, must change it, and enrols a new factor

### Requirement: Passwords meet a policy

Studio SHALL enforce a minimum length set by the administrator, a maximum the hash can hold, and differ from the username, and SHALL reject passwords found in the breached-password list; the online k-anonymity lookup SHALL be optional and off by default.

#### Scenario: Breached password

- **WHEN** a user sets a password on the list
- **THEN** it is rejected with a reason

#### Scenario: Offline

- **WHEN** the online lookup is off
- **THEN** the offline list alone is used and no password data leaves Studio

#### Scenario: Too short

- **WHEN** a password is shorter than the minimum
- **THEN** it is rejected with the minimum in the reason

### Requirement: Repeated failures lock the account

Studio SHALL lock an account after repeated consecutive failures, shared across every instance, until the lock expires or an administrator unlocks it, without revealing whether the account exists.

#### Scenario: Lockout

- **WHEN** failures exceed the limit
- **THEN** further attempts are refused until the lock expires or an administrator unlocks the account

#### Scenario: Unlock

- **WHEN** an administrator unlocks the account
- **THEN** the next correct sign-in proceeds and the unlock is audited

### Requirement: Sessions expire when idle and after an absolute lifetime

Studio SHALL end a session after a configurable period without user activity and after a configurable absolute lifetime, whichever comes first. Background refreshes that the user did not cause SHALL NOT count as activity.

#### Scenario: Idle session

- **WHEN** a session sees no user activity for longer than the idle period
- **THEN** its next request is unauthenticated

#### Scenario: A polling tab left open

- **WHEN** a tab keeps polling in the background but the user does nothing for longer than the idle period
- **THEN** the session ends

#### Scenario: Long-lived session

- **WHEN** a session stays active past the absolute lifetime
- **THEN** its next request is unauthenticated and the user signs in again

### Requirement: Users manage their own sessions

A user SHALL see their active sessions (start, last activity, address, client, which is the current one) and end any of them. A session's identifier SHALL never be shown.

#### Scenario: End another session

- **WHEN** a user ends a session
- **THEN** that session is rejected at its next request

### Requirement: Administrators manage everyone's sessions

An administrator with the right permission SHALL list and end any user's sessions.

#### Scenario: Without permission

- **WHEN** a caller lacks the permission
- **THEN** the list and the end action are refused

### Requirement: Authentication changes are audited

Enrolment, removal and reset of a factor, recovery-code use, a failed second factor, adding and revoking a trusted device, lockout, unlock, account recovery and session ends SHALL each produce an audit event naming the actor and the target.

#### Scenario: Factor removed

- **WHEN** a factor is removed
- **THEN** an audit event records who removed it and from which account
