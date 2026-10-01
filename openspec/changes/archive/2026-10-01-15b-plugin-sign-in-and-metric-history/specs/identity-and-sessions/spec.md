## ADDED Requirements

### Requirement: A plugin can contribute a credential sign-in provider
The plugin API SHALL let an active, verified plugin contribute a credential identity provider that it declares in its descriptor with an identifier of the form `<plugin id>:<name>` and a label. Studio SHALL run every sign-in through it on its one login path, so throttling, lockout, second factors, session issue, audit and group-to-role mapping apply unchanged. The provider SHALL only report whether the credentials match and, when they do, the external identity: subject, username, email and groups. Studio SHALL key the account by the declared provider identifier and the subject, never by anything else the plugin returns. A call into the provider SHALL be bounded to 5 seconds.

#### Scenario: Sign-in through a plugin provider
- **WHEN** a user signs in with a plugin provider and valid credentials
- **THEN** a session is issued, the user is provisioned by provider and subject, and their group mappings are applied

#### Scenario: The provider declines the credentials
- **WHEN** a plugin provider answers that the credentials do not match
- **THEN** the response is identical to a wrong local password, and the failure counts toward throttling and lockout

#### Scenario: A provider claims another provider's user
- **WHEN** a plugin provider returns a username equal to a local account's
- **THEN** it is a separate account keyed by the plugin's provider and subject, and never gains the local account's identity or grants

#### Scenario: A provider that fails
- **WHEN** a plugin provider throws, returns an invalid identity, or does not answer within 5 seconds
- **THEN** the sign-in fails like a wrong password, operational health reports the provider degraded with the reason, and local sign-in still works

#### Scenario: The provider recovers
- **WHEN** a provider reported degraded answers its next call
- **THEN** operational health no longer reports it

#### Scenario: An undeclared provider
- **WHEN** a plugin defines a provider whose identifier its descriptor does not declare, or declares one it does not define
- **THEN** its activation fails with a message naming the provider

#### Scenario: Step-up through a plugin provider
- **WHEN** a signed-in user of a plugin provider re-enters their password for a sensitive action
- **THEN** the provider checks it, and step-up succeeds only when it identifies the same account

## MODIFIED Requirements

### Requirement: The login screen offers the installation's configured identity providers

The system SHALL expose, without authentication, the list of configured identity providers. Each entry carries its identifier, its human label, and whether it is a credential provider (username and password) or a redirect provider (browser sign-in elsewhere), plus a redirect provider's start address. A plugin's credential provider SHALL be listed while that plugin is active and verified, with the label its descriptor declares, and the list SHALL be built without calling plugin code. The login screen SHALL be built only from this list:
- a username and password form when at least one credential provider exists, with a provider choice when there is more than one;
- one sign-in action per redirect provider.

The list SHALL NOT include providers that are not configured, nor providers of a plugin that is not active or not verified. A login naming such a provider SHALL fail exactly like a wrong password.

#### Scenario: Only local login is configured

- **WHEN** the installation has only the local provider
- **THEN** the login screen shows a username and password form with no provider choice and no sign-in buttons

#### Scenario: A redirect provider adds a sign-in action

- **WHEN** an OpenID Connect provider is configured alongside local login
- **THEN** the login screen shows the username and password form and a sign-in action labelled with that provider's label

#### Scenario: Two credential providers offer a choice

- **WHEN** two credential providers are configured
- **THEN** the login form offers a labelled choice between them, and the login request names the chosen provider

#### Scenario: A plugin provider joins the choice

- **WHEN** a verified plugin that declares a credential provider is activated
- **THEN** the login form offers it by its declared label next to local login

#### Scenario: The plugin is deactivated

- **WHEN** the plugin that contributes a provider is deactivated
- **THEN** the provider leaves the login screen, and a login naming it fails exactly like a wrong password

#### Scenario: The plugin's key is removed

- **WHEN** the key that signed a running plugin with a provider is removed from the trusted keys
- **THEN** its provider leaves the login screen at once, and a login naming it fails exactly like a wrong password

### Requirement: Revoking access ends sessions

The system SHALL end every session of each affected user when a user is disabled, when a grant is removed from a user, when a role's permissions change, or when the plugin provider that signs a user in reports that it no longer vouches for that user's identity. Sessions SHALL be stored in the database, so they are shared by every instance and can be found by user. At least every 5 minutes, Studio SHALL ask each active, verified plugin provider which of its enabled accounts' subjects it no longer vouches for; for each one it names, Studio SHALL also revoke the user's API tokens and trusted devices and audit the revocation, and SHALL NOT disable the account.

#### Scenario: A disabled user is signed out
- **WHEN** an administrator disables a signed-in user
- **THEN** that user's next request with the old session is unauthenticated

#### Scenario: A role change applies at once
- **WHEN** an administrator removes a permission from a role
- **THEN** every member's existing session ends and their next sign-in carries the new grants

#### Scenario: A user removed at the source
- **WHEN** a plugin provider reports that a signed-in user's identity is no longer valid
- **THEN** that user's next request with the old session or any of their API tokens is unauthenticated, and the event is audited with the provider and subject

#### Scenario: A user restored at the source
- **WHEN** a user whose identity was revoked is valid at the source again and signs in through the provider
- **THEN** sign-in succeeds into the same account

#### Scenario: A provider names a subject it was not asked about
- **WHEN** a provider's answer includes a subject that is not one of its enabled accounts
- **THEN** that subject is ignored

### Requirement: A local account can enrol TOTP and WebAuthn second factors

A user of a credential provider, the local provider or a plugin's, SHALL be able to enrol a TOTP authenticator, shown as a QR code and as text, and one or more WebAuthn or passkey credentials, and SHALL receive single-use recovery codes, shown once, at the first enrolment. Passkeys SHALL be offered only when Studio's public address is configured; otherwise the surface SHALL say so and name the setting. Adding, replacing or removing a factor while one is already enrolled SHALL require fresh authentication. A new TOTP secret SHALL NOT replace the active one until it is confirmed.

#### Scenario: Enrol TOTP

- **WHEN** a user scans the QR code and confirms a valid code
- **THEN** the factor is active and recovery codes are shown once

#### Scenario: A plugin provider's user enrols

- **WHEN** a user who signs in through a plugin's credential provider opens their account page
- **THEN** they can enrol a second factor, and are not offered a password change

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

### Requirement: An administrator can require MFA per role

Studio SHALL let an administrator mark any role, built-in or custom, as requiring MFA; the built-in administrator role SHALL require it by default. A user of a credential provider, the local provider or a plugin's, holding such a role SHALL not reach the console until a second factor is enrolled and verified. Users of redirect identity providers SHALL NOT be asked for Studio's second factor, since their provider owns authentication. Granting a user a role that requires MFA SHALL end their sessions. An API token minted without a verified second factor SHALL stop authenticating once its owner requires MFA.

#### Scenario: Required, not enrolled

- **WHEN** a user with a required role signs in without a factor
- **THEN** they are taken to enrolment and reach nothing else

#### Scenario: First enrolment is trust on first use

- **WHEN** a user with a required role and no factor signs in with the correct password
- **THEN** that session may enrol a factor, and the enrolment is audited with the source address

#### Scenario: A plugin provider's user with a required role

- **WHEN** a user who signs in through a plugin's credential provider holds a role that requires MFA and has no factor
- **THEN** they are taken to enrolment and reach nothing else

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

- **WHEN** a user signed in through a redirect identity provider holds a role that requires MFA
- **THEN** they reach the console and can mint tokens without Studio's second factor

#### Scenario: The last factor of a required user

- **WHEN** a user whose role requires MFA removes their only factor
- **THEN** it is refused until another factor is added
