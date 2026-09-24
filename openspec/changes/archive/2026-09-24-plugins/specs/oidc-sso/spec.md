## ADDED Requirements

### Requirement: Step-up re-authentication through the identity provider

A user signed in through an identity provider SHALL satisfy a fresh-authentication requirement by re-authenticating with that same provider, which the system SHALL ask to prompt for login and to accept an authentication no older than 5 minutes. The step-up SHALL succeed only when the returning identity is the same provider and subject as the session's, and the provider reports an authentication time within the last 5 minutes; when the provider reports no authentication time the step-up SHALL fail. A successful step-up SHALL keep the user's session and return them to where they started it.

#### Scenario: A different account cannot satisfy step-up

- **WHEN** a user starts a step-up and completes it at the provider as a different subject
- **THEN** the step-up fails and the session's fresh-authentication time is unchanged

#### Scenario: A provider without authentication time fails closed

- **WHEN** the provider's response carries no authentication time
- **THEN** the step-up fails and states that the identity provider does not report one
