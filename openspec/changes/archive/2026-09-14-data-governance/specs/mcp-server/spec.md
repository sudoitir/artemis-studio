## ADDED Requirements

### Requirement: Message content through the agent surface is governed like the API

A message body or property returned through the agent surface SHALL be governed by the same content policy, and for the same token identity, as the equivalent API read. A value masked, dropped or withheld for that identity SHALL be marked as such in the result, so the model cannot mistake a marker for data.

#### Scenario: An assistant receives masked content

- **WHEN** an assistant using a token without clear access fetches one message body that contains a card number
- **THEN** the result shows the card number partially masked and identifies it as a masked payment card number

#### Scenario: A credential never reaches an assistant

- **WHEN** an assistant using a token holding clear access fetches a message with an `Authorization` property
- **THEN** the result shows the credential dropped
