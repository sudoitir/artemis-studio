## ADDED Requirements

### Requirement: RabbitMQ is modelled honestly
The RabbitMQ driver SHALL map virtual hosts to namespaces, exchanges to destinations, queues to subscriptions, and bindings, exchange-to-exchange bindings, shovels and federation links to routes. It SHALL map policies to policies and permissions to access rules. Quorum queue members SHALL appear as leader and follower placements.

#### Scenario: A quorum queue's members
- **WHEN** a quorum queue runs on three nodes
- **THEN** its placements name the leader and the two followers

### Requirement: Browse never consumes
The driver SHALL offer browse only where it is non-destructive (stream queues). It SHALL declare browse unavailable for classic and quorum queues rather than get and requeue messages.

#### Scenario: Browsing a classic queue
- **WHEN** an operator opens the messages of a classic queue
- **THEN** browse is shown as unavailable with the reason, and no message is fetched

### Requirement: The RabbitMQ driver passes the TCK
The driver SHALL pass every TCK case for the capabilities it declares, against a real RabbitMQ cluster.

#### Scenario: CI runs the TCK
- **WHEN** the driver's integration tests run
- **THEN** every declared capability's TCK cases pass
