## ADDED Requirements

### Requirement: ActiveMQ Classic is modelled honestly
The Classic driver SHALL map queues and topics to destinations, queues and durable subscribers to subscriptions, and virtual topics, composite destinations and network connectors to routes. It SHALL map destination policies to policies and authorization entries to access rules.

#### Scenario: A virtual topic
- **WHEN** a virtual topic feeds two consumer queues
- **THEN** the topic, the two queues and the two routes between them are listed

### Requirement: Browse depth is declared
The driver SHALL declare the broker's maximum browse depth as a capability, and report totals beyond it as exact counts with a truncated browse.

#### Scenario: A queue deeper than the browse limit
- **WHEN** an operator browses a queue holding more messages than the browse depth
- **THEN** the page says how far browse can reach, and the total is still exact

### Requirement: The Classic driver passes the TCK
The driver SHALL pass every TCK case for the capabilities it declares, against a real ActiveMQ Classic broker.

#### Scenario: CI runs the TCK
- **WHEN** the driver's integration tests run
- **THEN** every declared capability's TCK cases pass
