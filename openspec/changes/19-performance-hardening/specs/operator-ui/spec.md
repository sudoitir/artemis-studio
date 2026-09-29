## ADDED Requirements

### Requirement: Large lists render in time independent of their length
Every grid that can list more than a few hundred rows SHALL render only what is visible, so that opening and scrolling it stays responsive at the sizes in the sizing guide.

#### Scenario: A very large grid opens
- **WHEN** a grid receives the largest size in the guide
- **THEN** it becomes interactive within the guide's stated time and scrolling holds the guide's stated frame rate

#### Scenario: Keyboard and selection still work
- **WHEN** a user selects or searches in a large grid
- **THEN** focus, selection and find behave as in a small grid

### Requirement: A view that needs many broker reads makes them in batches
Views that read the same kind of value from many resources SHALL request them in batches rather than one call per resource, within the existing rate limits.

#### Scenario: A large cluster view loads
- **WHEN** a view covers many queues
- **THEN** the number of broker calls grows with the number of batches, not the number of queues
