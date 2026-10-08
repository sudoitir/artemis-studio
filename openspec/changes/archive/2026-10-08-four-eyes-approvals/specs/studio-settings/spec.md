## ADDED Requirements

### Requirement: Settings changes SHALL be applied as one change set

Studio SHALL accept several setting changes and resets as one change set, applied together or not at all. Studio SHALL offer a preview that validates a change set and says whether it would apply now, be held for approval (with the policy and whether a reason is needed), or be denied. A single-setting write SHALL be a change set of one.

#### Scenario: All or nothing

- **WHEN** a change set holds one valid and one invalid value
- **THEN** nothing is changed and the invalid field is reported

#### Scenario: Preview says held

- **WHEN** an operator previews a change set that a provider would hold
- **THEN** the preview says it needs approval and whether a reason is required

### Requirement: Settings SHALL be described by category, default and pending state

Each setting the API lists SHALL carry its category and its category title, its default value, and any held change waiting for approval. A pending change SHALL show who requested it and when.

#### Scenario: Pending change listed

- **WHEN** a change to a setting is held for approval
- **THEN** listing settings shows the pending value, its requester and the request
