## ADDED Requirements

### Requirement: Audit parameters never carry a sensitive value

Before an audit record is written, its parameters SHALL be passed through the content policy. That covers query text, filter expressions and selector strings. Values the policy detects as sensitive, and literals compared with classified fields, SHALL be masked, and credentials SHALL be dropped. Masking SHALL NOT change the record's action, target or outcome.

#### Scenario: A move by filter does not store the filter literal

- **WHEN** an operator moves messages with the filter `email = 'jane@example.com'`
- **THEN** the audit record's filter parameter shows the literal redacted

### Requirement: Governance actions and clear views are audited

The system SHALL write an audit record for each of these actions:

- creating, changing, enabling, disabling and deleting a masking rule;
- confirming and dismissing a finding;
- every response that served sensitive values in clear.

A clear-view record SHALL carry the classes and counts served and SHALL NOT carry any value.

#### Scenario: Disabling a built-in rule is audited

- **WHEN** an administrator disables the built-in `Authorization` rule
- **THEN** an audit record names the actor, the rule and the change
