## MODIFIED Requirements

### Requirement: Dry-run registration persists nothing

When registration is requested with `dryRun` true, the system SHALL perform the
probe and topology discovery and return the capabilities and discovered topology,
and SHALL NOT create a cluster, node, credential, TLS, or any other row. The
discovered topology SHALL be returned in the same structural shape used to render
a registered cluster's topology graph, so the operator can be shown a preview of
what would be saved.

#### Scenario: Dry run returns a preview

- **WHEN** registration is called with `dryRun=true` against a live broker
- **THEN** the response contains capabilities and discovered topology
- **AND** no cluster row exists afterward

#### Scenario: Dry run still audited

- **WHEN** a dry-run registration is called
- **THEN** an audit event is written recording the attempt with its dry-run flag set

#### Scenario: Preview topology renders like a saved cluster's

- **WHEN** the registration UI receives a dry-run preview
- **THEN** it renders the discovered topology using the same graph presentation
  shown for an already-registered cluster, with every node's status shown as
  unknown since no health has been polled yet
