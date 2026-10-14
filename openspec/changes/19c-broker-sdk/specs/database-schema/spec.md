## ADDED Requirements

### Requirement: The schema uses broker-neutral names
Tables, columns and stored enum values SHALL use the SDK's neutral vocabulary (system, destination, subscription, kind, plane, native id) and store native details as namespaced attributes. Every cluster SHALL record its system. Node endpoints SHALL be stored per plane, not as system-specific URL columns. Stored metric names SHALL be the SDK's standard metric keys.

#### Scenario: A fresh install
- **WHEN** Studio creates its schema
- **THEN** no table, column or stored enum value names an Artemis concept

#### Scenario: A node with two planes
- **WHEN** an Artemis node is registered
- **THEN** its management and data endpoints are stored as two endpoint rows, one per plane
