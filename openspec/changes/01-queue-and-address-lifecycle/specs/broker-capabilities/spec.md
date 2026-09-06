## MODIFIED Requirements

### Requirement: Management write authority is established by evidence, not inferred

The system SHALL classify a connection's management-write capability from evidence
of an actual write, never from the success of a read.

The capability SHALL be reported as unknown until a management write has been
attempted on that connection; as available once one has succeeded; and as
unavailable once one has been refused for an authorization reason, with the
`broker.xml` snippet that grants the missing management permission.

A write that fails for a reason other than authorization — an unreachable broker,
or an argument the broker rejects — SHALL NOT change the assessment, so that one
malformed request cannot permanently present a connection as unable to write.

While the capability is unknown, the system SHALL offer write operations and SHALL
state that the first one will establish whether the connection can perform them.
It SHALL NOT hide the operations, and SHALL NOT claim an authority it has not
observed.

#### Scenario: A newly registered connection reports unknown

- **WHEN** a connection is registered and only read operations have been performed
- **THEN** its management-write capability is reported as unknown, and write
  operations are offered with that stated

#### Scenario: A successful write establishes the capability

- **WHEN** a management write succeeds on a connection
- **THEN** its management-write capability is reported as available

#### Scenario: An authorization refusal establishes unavailability with the fix

- **WHEN** a management write is refused because the configured management user
  lacks permission
- **THEN** the capability is reported as unavailable, with the reason and the
  `broker.xml` snippet that would grant it

#### Scenario: A rejected argument does not disable the capability

- **WHEN** a management write fails because the broker rejects one of its arguments
- **THEN** the management-write capability is left as it was, and later writes are
  still offered
