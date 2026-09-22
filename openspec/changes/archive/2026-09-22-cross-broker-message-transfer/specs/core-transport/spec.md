## MODIFIED Requirements

### Requirement: The Core connection is configured not to be driven by broker topology

The system SHALL open Core connections with client-side topology-driven load
balancing disabled and the client library's automatic reconnect disabled, so
that a connector hostname the broker advertises but Studio cannot resolve does
not cause a blocking retry. TLS settings for a Core connection SHALL be resolved
from the same per-cluster TLS reference used for the Jolokia connection, and
SHALL apply to that cluster's connections only: configuring TLS for one cluster
SHALL NOT change the trust material any other cluster's Core connection uses.

#### Scenario: An unresolvable advertised connector does not wedge the connection

- **WHEN** the broker advertises a Core connector hostname that does not resolve from where Studio runs
- **THEN** the Core connection attempt fails fast and is retried by Studio rather than blocking on a library reconnect loop

#### Scenario: TLS reference is shared

- **WHEN** a cluster has a TLS reference configured
- **THEN** Core connections for that cluster use the same trust material as its Jolokia connection

#### Scenario: Two clusters with different certificate authorities

- **WHEN** two clusters each have a TLS reference whose trust material is a different certificate authority
- **THEN** Core connections to both clusters succeed at the same time, and each trusts only its own cluster's authority

#### Scenario: An undefined TLS reference fails that cluster only

- **WHEN** a cluster's TLS reference names trust material that is not defined
- **THEN** that cluster's Core connection fails with a reason naming the missing reference, and other clusters' Core connections are unaffected

## ADDED Requirements

### Requirement: A transacted relay session moves messages between two nodes

The system SHALL provide, for moving messages from a queue on one node to a queue
on another, a relay whose source receives and target sends are each transacted.
A batch SHALL be committed on the target before it is acknowledged on the source,
and every relayed message SHALL carry a duplicate-detection id derived from the
run and the source message, so that a batch repeated after a failure between the
two commits is dropped by the target broker. Relay sessions SHALL be separate from
operator browse/send sessions and from capture sessions, so a relay cannot starve
either.

#### Scenario: A failure before the target commit loses nothing

- **WHEN** the target send or commit fails for a batch
- **THEN** the source receive is rolled back and the batch's messages remain on the source

#### Scenario: A failure between the two commits does not duplicate

- **WHEN** the target commit succeeds and the source acknowledgement fails, and the batch is relayed again
- **THEN** the target broker drops the repeated messages as duplicates and the target holds each message once

#### Scenario: A relay does not starve operator sessions

- **WHEN** a relay is running against a node
- **THEN** browsing and sending from that node's messages view still obtain a session
