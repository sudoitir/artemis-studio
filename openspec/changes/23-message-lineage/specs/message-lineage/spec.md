## ADDED Requirements

### Requirement: A message's path can be followed across observed hops
The system SHALL assemble the known path of a message across queues, diverts, bridges, cross-broker transfers, dead-letter queues and captured payloads from what Studio observes, ordered by time.

#### Scenario: A diverted message
- **WHEN** a message is diverted and later dead-lettered
- **THEN** its path shows the source queue, the divert and the DLQ with times

#### Scenario: A transferred message
- **WHEN** a message was moved by a cross-broker transfer
- **THEN** the path links the source and target through the provenance the transfer stamped

#### Scenario: A bridged message
- **WHEN** a message crosses a bridge to another registered cluster
- **THEN** the path continues on the target cluster

### Requirement: Correlation keys are explicit and configurable
The system SHALL correlate hops by message ID and correlation ID and by additional headers an administrator configures, and SHALL state which key linked each hop.

#### Scenario: A header is configured
- **WHEN** an administrator adds a business header as a key
- **THEN** hops sharing it are linked and labelled with that key

#### Scenario: A key is ambiguous
- **WHEN** several unrelated messages share a key
- **THEN** the path shows the ambiguity and does not merge them silently

### Requirement: A path never claims more than was observed
Where Studio saw no evidence between two known hops, the path SHALL show an unknown segment. It SHALL NOT infer a hop it did not observe.

#### Scenario: A gap exists
- **WHEN** a message appears on a queue with no observed predecessor
- **THEN** the graph shows an unknown origin

#### Scenario: Capture was off
- **WHEN** a hop happened where capture was not armed
- **THEN** the hop is shown as unobserved with the reason

### Requirement: The path is shown as a graph with timestamps
The interface SHALL show a message's path as a graph with hops, times and the elapsed time between them, and link each hop to the queue, the captured payload and any traced flow.

#### Scenario: An operator opens a path
- **WHEN** a lineage exists
- **THEN** the graph shows each hop with its time and links to its sources

### Requirement: Lineage storage is bounded and governed
Lineage records SHALL follow the retention and quota rules of the data lifecycle policy, SHALL respect data-governance masking, and SHALL never keep more than the configured bound.

#### Scenario: Retention expires
- **WHEN** a record is past its retention
- **THEN** it is purged and the graph shows the earlier hops as no longer retained

#### Scenario: A masked field
- **WHEN** a correlation header is masked by governance
- **THEN** the value is not shown to a viewer without the right to see it

### Requirement: Viewing lineage requires permission on every hop's cluster
A viewer SHALL only see hops on clusters they may read; hops elsewhere SHALL appear as hidden, not as unknown.

#### Scenario: A path crosses to a cluster the viewer cannot read
- **WHEN** the viewer opens it
- **THEN** that hop is shown as hidden and its details are not disclosed
