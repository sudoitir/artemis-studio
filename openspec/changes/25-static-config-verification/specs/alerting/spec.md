## ADDED Requirements

### Requirement: The configuration drift condition covers static settings
The existing configuration drift condition SHALL fire on static drift as it does on other drift, and the notification SHALL name the node, the setting and both values.

#### Scenario: Static drift persists
- **WHEN** a node's global size stays different from the declaration past the rule's pending duration
- **THEN** a bound drift rule fires naming the node, the setting and both values

#### Scenario: Unverifiable is not drift
- **WHEN** a declared static setting cannot be read from a node
- **THEN** the drift rule does not fire on it
