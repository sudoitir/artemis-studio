## ADDED Requirements

### Requirement: Message capture is a capability, reported three-state with its snippet

The system SHALL report, per connection, whether it can install a message capture tap. The
answer SHALL be three-state — available, not available, or not yet established — on the same
terms as every other capability.

Capture requires more than management write: it requires the authority to manage diverts, to
create an address and a queue, to set an address setting, and to restrict access to the
address it creates. Where any of these is refused, the system SHALL name **which** one was
refused, because they are granted separately and an operator told only "capture is
unavailable" cannot act on it.

Each unavailable element SHALL ship the broker configuration that would grant it.

Capture SHALL NOT be blocked because the capability has not yet been established. An
unattempted capability is unknown, not unavailable, and the attempt itself is what
establishes it.

#### Scenario: A refused element is named with its remedy

- **WHEN** Studio's broker identity may manage diverts but may not set a security setting on the address it would create
- **THEN** the capability reports that specific refusal and ships the configuration that would grant it

#### Scenario: An unestablished capture capability does not block the operator

- **WHEN** no capture has been attempted on a connection
- **THEN** the capability is reported as not yet established and the operator is still offered the action
