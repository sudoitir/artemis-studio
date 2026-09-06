## REMOVED Requirements

### Requirement: MANAGEMENT_WRITE is inferred without mutating the broker

**Reason**: The inference was never evidence. A successful read-only
`listNetworkTopology()` proves that Jolokia is not under a read-only policy and
that the caller cleared `manage` — it does not prove any particular write is
permitted, because a `jolokia-access.xml` can whitelist per operation and the
broker's own security settings are a separate gate. Nothing depended on the
inference until queue lifecycle did, and a create button resting on a guess is
the capability model lying to the operator (non-negotiable #5).

**Migration**: A connection previously reported `AVAILABLE` on inference alone is
now reported as unknown until its first management write. That is a correction,
not a regression, and write operations continue to be offered while it is unknown.
The prohibition on probing by creating throwaway broker objects is retained by the
replacement requirement below, not dropped.

## ADDED Requirements

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

#### Scenario: Probing still creates nothing

- **WHEN** the capability probe runs
- **THEN** no address or queue is created or deleted on the broker at any point,
  and the write capability is reported from recorded evidence rather than from a
  probe of its own

## ADDED Requirements

### Requirement: A node's effective configuration is readable on its own

The system SHALL report the configuration one node is effectively running with —
broker attributes, address settings, security settings and acceptors, as the broker
resolves them — addressable by that node alone, without requiring a second node to
compare it against.

This SHALL be the broker's resolved runtime configuration, never the contents of a
configuration file: the system does not read or write `broker.xml`.

A node that cannot be read SHALL be reported as unavailable with the reason. An
empty configuration SHALL NOT be returned in its place, because an absence
presented as a fact is indistinguishable from a node that is genuinely configured
with nothing.

Where the number of address-setting matches resolved is capped, the response SHALL
state how many were read of how many were known.

#### Scenario: One node's settings are readable alone

- **WHEN** an operator asks what a single node is configured with
- **THEN** its effective configuration is returned without naming a second node

#### Scenario: An unreadable node is not reported as unconfigured

- **WHEN** a node's configuration cannot be read
- **THEN** the result says so and gives the reason, rather than returning an empty
  configuration
