## ADDED Requirements

### Requirement: Content governance permissions

The system SHALL define three permissions:

- `message:clear` shows sensitive message values in clear at the granted scope;
- `governance:read` reads masking rules, findings and re-mask progress;
- `governance:write` changes rules and confirms or dismisses findings.

Wildcard grants SHALL include these permissions as they include any other. The built-in operator and viewer roles SHALL NOT include `message:clear` or `governance:write`. Holding the message read permission SHALL NOT imply `message:clear`.

#### Scenario: Message read does not imply clear access

- **WHEN** a user whose only message permission is `message:read` opens a message holding an email address
- **THEN** the email is masked

#### Scenario: A wildcard grant includes clear access

- **WHEN** a user holding `*` at global scope opens that message
- **THEN** the email is shown in clear and the clear view is audited
