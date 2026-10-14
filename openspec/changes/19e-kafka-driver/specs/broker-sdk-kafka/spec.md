## ADDED Requirements

### Requirement: Kafka is modelled honestly
The Kafka driver SHALL map topics to destinations, partitions to partitions, consumer groups to consumer groups, and a consumer group on one topic to a subscription. It SHALL report a subscription's backlog as an estimate with a per-partition breakdown. It SHALL declare per-message move, copy and delete unavailable.

#### Scenario: Lag is shown per partition
- **WHEN** a group lags on a topic with six partitions
- **THEN** the subscription shows the summed backlog as an estimate, and each partition's share

#### Scenario: Moving a message is not offered
- **WHEN** an operator opens a Kafka message
- **THEN** move and delete are shown as unavailable, with the reason

### Requirement: Destructive operations state their reach
Purging a subscription SHALL seek its group to the end and affect only that group. Truncating a topic or partition SHALL delete records for every group. Both SHALL be previewed with their effect and guarded by a state key.

#### Scenario: Truncate warns about other groups
- **WHEN** an operator previews truncating a topic that three groups read
- **THEN** the preview says the data is removed for all three groups

### Requirement: The Kafka driver passes the TCK
The driver SHALL pass every TCK case for the capabilities it declares, against a real Kafka cluster.

#### Scenario: CI runs the TCK
- **WHEN** the driver's integration tests run
- **THEN** every declared capability's TCK cases pass
