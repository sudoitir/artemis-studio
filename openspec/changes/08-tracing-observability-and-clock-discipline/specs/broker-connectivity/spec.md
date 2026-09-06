## ADDED Requirements

### Requirement: Each broker's clock offset is measured without extra calls

The system SHALL estimate how far each broker node's clock differs from its own,
using a timestamp the broker already returns on management responses, and SHALL
NOT make any additional broker request to obtain it.

Each estimate SHALL carry an uncertainty covering both the round trip and the
granularity of the source timestamp. An offset within its own uncertainty SHALL be
treated as agreement. A node whose responses carry no usable timestamp SHALL be
reported as unmeasured, never as being in agreement.

#### Scenario: Offset is measured during ordinary polling

- **WHEN** the system polls a broker node
- **THEN** it records an estimate of that node's clock offset without issuing a
  further request

#### Scenario: A sub-uncertainty offset is not skew

- **WHEN** a node's measured offset is smaller than its uncertainty
- **THEN** the node is reported as being in agreement and no correction is applied

#### Scenario: A missing timestamp is unknown

- **WHEN** a node's responses carry no usable timestamp
- **THEN** its clock is reported as unmeasured rather than as agreeing

### Requirement: Broker timestamps are normalised before they are stored

The system SHALL convert a timestamp originating from a broker onto its own
timeline, using that node's measured offset, before comparing or persisting it
alongside its own timestamps.

#### Scenario: A deadline from a skewed clock is corrected

- **WHEN** a message carries an absolute expiry stamped by a clock that the system
  has measured as offset
- **THEN** the deadline the system acts on is expressed on its own timeline

### Requirement: A wrong clock on the system's own host is inferred and reported

The system SHALL distinguish individual nodes disagreeing with it from every
measured node disagreeing with it in the same direction. The latter SHALL be
reported as implicating the system's own host rather than the brokers.

The system SHALL also detect its own wall clock stepping relative to elapsed time,
and SHALL discard its clock estimates when that happens rather than carrying them
forward.

#### Scenario: One node out of step names that node

- **WHEN** one measured node disagrees beyond tolerance and others agree
- **THEN** the verdict names that node

#### Scenario: Every node out of step implicates the host

- **WHEN** every measured node disagrees beyond tolerance in the same direction
- **THEN** the verdict states that the system's own host is the likely cause

#### Scenario: A stepped clock invalidates the estimates

- **WHEN** the system's wall clock moves relative to elapsed time beyond a
  threshold
- **THEN** the existing clock estimates are discarded and rebuilt
