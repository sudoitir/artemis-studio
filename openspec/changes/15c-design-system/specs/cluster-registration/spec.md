## MODIFIED Requirements

### Requirement: Dry-run registration persists nothing

When registration is requested with `dryRun` true, the system SHALL perform the
probe and topology discovery and return the capabilities and discovered topology,
and SHALL NOT create a cluster, node, credential, TLS, or any other row. The
discovered topology SHALL be returned in the same structural shape used to render
a registered cluster's topology graph. The registration form states what the check
found in words (the node count) and does not draw the topology: the graph appears
on the cluster's Topology page once it is registered.

#### Scenario: Dry run returns a preview

- **WHEN** registration is called with `dryRun=true` against a live broker
- **THEN** the response contains capabilities and discovered topology
- **AND** no cluster row exists afterward

#### Scenario: Dry run still audited

- **WHEN** a dry-run registration is called
- **THEN** an audit event is written recording the attempt with its dry-run flag set

#### Scenario: The check shows what could be configured after registering

- **WHEN** a dry-run registration reaches a broker with a capability gap Studio
  could close over the management API
- **THEN** the preview carries the same recommendations the registered cluster
  would show, seeded from the node the check reached, presented as a preview that
  cannot yet be declared — there is no cluster for a revision to belong to
- **AND** registering then lands the operator on that cluster's recommended
  configuration, where the same panel can be declared and applied

#### Scenario: Preview topology renders like a saved cluster's

- **WHEN** the registration UI receives a dry-run preview
- **THEN** it states in words how many nodes the check found, without drawing a graph
- **AND** once the cluster is registered, its Topology page draws the discovered nodes with the same graph
  presentation as every registered cluster
