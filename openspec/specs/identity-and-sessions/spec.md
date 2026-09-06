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

The system SHALL authenticate a local user by username and password, hashed at
rest, and SHALL establish a server-side session on success, identified to the
client by an HTTP-only cookie. The system SHALL expose an endpoint that returns
the current principal's identity, roles, and grants, and an endpoint that ends
the session.

#### Scenario: Successful login establishes a session

- **WHEN** a user submits a correct username and password
- **THEN** a session is created, a session cookie is set, and subsequent
  requests with that cookie are authenticated as that user

#### Scenario: Wrong password is rejected

- **WHEN** a user submits an incorrect password
- **THEN** authentication fails and no session is created

#### Scenario: Logout ends the session

- **WHEN** an authenticated user calls logout
- **THEN** the session is invalidated and the same cookie no longer
  authenticates subsequent requests

#### Scenario: Current identity is readable

- **WHEN** an authenticated user requests their own identity
- **THEN** the response includes their username, roles, and effective grants

### Requirement: Repeated failed logins are throttled

The system SHALL limit the rate of failed login attempts for a given username
and source IP, rejecting further attempts for a backoff period after
repeated failures, and SHALL record each failed attempt in the audit trail.

#### Scenario: Lockout after repeated failures

- **WHEN** a caller submits several consecutive wrong passwords for the same
  username from the same source
- **THEN** further login attempts from that source are rejected until the
  backoff period elapses, even with the correct password

#### Scenario: A successful login clears the failure count

- **WHEN** a caller authenticates successfully after prior failed attempts
- **THEN** the failure count for that username and source is reset

### Requirement: A fresh instance bootstraps one administrator

When no user account exists, the system SHALL create a single administrator
account on startup with a randomly generated password, disclosed exactly once
in the startup log, and SHALL require that password to be changed before the
account can be used for anything else.

#### Scenario: First boot creates the admin account

- **WHEN** the application starts against a database with no user accounts
- **THEN** exactly one administrator account is created and its generated
  password is written to the startup log once

#### Scenario: Subsequent boots do not re-bootstrap

- **WHEN** the application starts against a database that already has a user
  account
- **THEN** no new account is created

#### Scenario: Login is restricted until the password is changed

- **WHEN** the bootstrap administrator logs in with the generated password
- **THEN** every request other than changing the password is rejected until
  the password has been changed

### Requirement: A user can change their own password

The system SHALL allow an authenticated user to change their own password by
supplying their current password, and SHALL reject the change if the current
password does not match.

#### Scenario: Password change succeeds

- **WHEN** a user submits their correct current password and a new password
- **THEN** the password is updated and a subsequent login uses the new password

#### Scenario: Password change rejects a wrong current password

- **WHEN** a user submits an incorrect current password
- **THEN** the password is not changed

### Requirement: A user has an account page for their own identity and credentials

The system SHALL provide every authenticated user, regardless of role, a self-service
account surface reachable from the application's user menu. It SHALL show who the user is
signed in as, the source of that identity, and a summary of the grants they hold; and it
SHALL be the single place a user manages their own password and their own API keys.

Personal API keys SHALL NOT also be manageable from the administration surface. Managing
one's own credentials is not an administrative act, and two surfaces for one thing is one
too many.

#### Scenario: Every user reaches their account

- **WHEN** a user with no administrative permission opens the user menu
- **THEN** the account surface is offered and opens

#### Scenario: The account surface shows the caller's own identity and grants

- **WHEN** a user opens their account
- **THEN** their username, the source of their identity, and the grants they hold are shown

#### Scenario: Keys are managed only from the account surface

- **WHEN** a user with administrative permission opens the administration surface
- **THEN** personal API key management is not offered there

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
