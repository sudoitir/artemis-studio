## ADDED Requirements

### Requirement: Signing in issues a new session identifier

Every successful sign-in, through any identity provider, and every successful step-up re-authentication SHALL issue a new session identifier, so that a session identifier known before authentication never becomes authenticated.

#### Scenario: A pre-login session identifier is not authenticated

- **WHEN** a client holding a session identifier signs in successfully
- **THEN** the response carries a different session identifier, and the earlier one does not authenticate any request

### Requirement: Sensitive actions can require fresh authentication

The system SHALL record when a session last authenticated. An action that requires fresh authentication SHALL be refused with `403` and problem type `reauthentication-required` when that was more than 5 minutes ago — not `401`, because the caller is still signed in and a client treats `401` as "sign in again". A user of a credential provider SHALL re-authenticate by re-entering their password. Step-up attempts SHALL be throttled like logins, and after 5 consecutive failed attempts the session SHALL be ended.

#### Scenario: Re-entering the password satisfies step-up

- **WHEN** a user whose session is an hour old re-enters their correct password and then repeats the action
- **THEN** the action proceeds

#### Scenario: Repeated wrong passwords end the session

- **WHEN** five consecutive step-up attempts in one session fail
- **THEN** the session is ended and the user must sign in again
