## MODIFIED Requirements

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

## ADDED Requirements

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
