## ADDED Requirements

### Requirement: Node comparison and declaration comparison are distinguished and linked

The system SHALL present its node-to-node configuration comparison and its
comparison against a declared expected state as two distinct views, each stating
what it compares, and SHALL link each to the other.

Both SHALL read a node's effective configuration from the same source, so that they
cannot report different values for the same node.

#### Scenario: An operator can tell the two comparisons apart

- **WHEN** an operator opens either comparison
- **THEN** it states whether it is comparing nodes to each other or to the declared
  expected state, and links to the other

#### Scenario: The two never disagree about a node

- **WHEN** both comparisons report on the same node's configuration
- **THEN** the values they report for that node are identical
