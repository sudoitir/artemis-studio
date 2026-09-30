## MODIFIED Requirements

### Requirement: Content that cannot be inspected is withheld with its reason

For a user without clear access, the system SHALL withhold:

- a message body it cannot inspect, meaning a binary body or a body of an unrecognised format;
- the portion of a body beyond the configured scan limit.

A bytes message whose body is read as text (see message-operations) is not a binary body: it SHALL be
inspected, masked and scanned exactly as a text body is, and SHALL NOT be withheld for being a bytes
message.

A withheld body or portion SHALL be neither shown nor stored in clear. The response SHALL state what was withheld and why. Where the reason is the scan limit, the response SHALL name the setting that changes it. Header and property rules and detectors SHALL still apply to such a message.

#### Scenario: A binary body is withheld

- **WHEN** a user without clear access opens a message with a binary body
- **THEN** the body is not shown, and the response states that a binary body cannot be classified and was withheld

#### Scenario: Text sent as bytes is masked, not withheld

- **WHEN** a user without clear access opens a bytes message whose body is UTF-8 JSON with a masking
  rule on one of its paths
- **THEN** the body is shown with that path masked, and it is not reported as withheld

#### Scenario: Bytes past the scan limit are withheld

- **WHEN** a text body is larger than the scan limit
- **THEN** only the scanned portion is shown (masked where needed), and the response states that the remainder was not scanned and names the scan-limit setting
