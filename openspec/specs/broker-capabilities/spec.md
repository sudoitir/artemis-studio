## Purpose

Defines the capability probe: how Artemis Studio classifies what a given broker
connection can do, the three-state status model, and the contract that an
unavailable feature is always explained and always accompanied by the exact
`broker.xml` change that would enable it.

## Requirements

### Requirement: A connection is classified into four capability classes

For every registered cluster the system SHALL determine, per connection, a
status for each of `MANAGEMENT_READ`, `MANAGEMENT_WRITE`, `NOTIFICATIONS`, and
`MESSAGE_IO`.

#### Scenario: Probe runs at registration

- **WHEN** a cluster is registered or its connection is checked
- **THEN** the response includes a status for all four capability classes

### Requirement: Capability status is three-state with a reason

Each capability status SHALL be exactly one of `AVAILABLE`, `UNAVAILABLE`, or
`UNKNOWN`, and SHALL carry a human-readable reason string. `UNKNOWN` SHALL be
used when Studio cannot determine the capability without taking an action it is
not permitted to take in this phase.

#### Scenario: Reason accompanies every status

- **WHEN** a capability status is returned
- **THEN** it includes a non-empty reason explaining how the status was reached

### Requirement: MANAGEMENT_READ reflects a successful read

`MANAGEMENT_READ` SHALL be `AVAILABLE` when the broker MBean is resolved and a
broker attribute read returns success, and `UNAVAILABLE` otherwise.

#### Scenario: Reads succeed

- **WHEN** the `search` resolves the broker MBean and an attribute read returns 200
- **THEN** `MANAGEMENT_READ` is `AVAILABLE`

#### Scenario: Reads rejected

- **WHEN** attribute reads return 403
- **THEN** `MANAGEMENT_READ` is `UNAVAILABLE` with a reason naming the rejection

### Requirement: MANAGEMENT_WRITE is inferred without mutating the broker

`MANAGEMENT_WRITE` SHALL be determined by invoking a read-only management
operation (`listNetworkTopology()`) and observing whether it is permitted. The
system SHALL NOT create, delete, or modify any broker object to probe write
access. When the inference is positive the reason string SHALL state that it is
an inference and that a per-operation Jolokia policy could still restrict
specific writes.

#### Scenario: Read-only exec succeeds

- **WHEN** `listNetworkTopology()` returns success
- **THEN** `MANAGEMENT_WRITE` is `AVAILABLE` and the reason states it was inferred
  from a read-only operation

#### Scenario: Exec is forbidden

- **WHEN** `listNetworkTopology()` returns a permission error
- **THEN** `MANAGEMENT_WRITE` is `UNAVAILABLE`

#### Scenario: No throwaway objects are created

- **WHEN** the capability probe runs
- **THEN** no address or queue is created or deleted on the broker at any point

### Requirement: NOTIFICATIONS is UNKNOWN with detectable preconditions reported

`NOTIFICATIONS` SHALL be `UNKNOWN` in this phase, because confirming it requires
a protocol client that does not yet exist. The probe SHALL still report which
preconditions it can observe over Jolokia: whether a CORE-protocol acceptor
exists and whether the `activemq.notifications` address exists.

#### Scenario: Preconditions visible

- **WHEN** the broker has a CORE acceptor and the `activemq.notifications` address
- **THEN** `NOTIFICATIONS` is `UNKNOWN` and the reason notes both preconditions
  are present

#### Scenario: Precondition missing

- **WHEN** the broker exposes no CORE-protocol acceptor
- **THEN** `NOTIFICATIONS` is `UNKNOWN` and the reason notes the CORE acceptor is
  absent

### Requirement: Every not-available capability ships a broker.xml snippet

When a capability is `UNAVAILABLE`, or `UNKNOWN` for a reason the operator can
fix in configuration, the capability result SHALL include the exact `broker.xml`
snippet that would enable it. The `NOTIFICATIONS` snippets SHALL include both a
`security-setting` for `activemq.notifications` granting `consume`,
`createNonDurableQueue`, and `deleteNonDurableQueue` in one block, and a
`NotificationActiveMQServerPlugin` configuration enabling connection, session,
delivered, and expired notifications.

#### Scenario: Notifications hint is complete

- **WHEN** the `NOTIFICATIONS` capability is returned
- **THEN** it includes a `security-setting` snippet that restates every required
  permission in a single most-specific block, and a plugin snippet with the four
  `SEND_*` flags

#### Scenario: No silent gaps in the UI

- **WHEN** the frontend renders a capability that is not `AVAILABLE`
- **THEN** it shows the reason and the `broker.xml` snippet rather than hiding
  the related controls
