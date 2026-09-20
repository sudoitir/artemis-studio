## ADDED Requirements

### Requirement: A health verdict is carried in words, with colour as redundant emphasis

A view presenting a derived verdict SHALL state it as a word. Colour SHALL NOT be the only
carrier of the verdict, and SHALL be applied only where something is wrong, so a healthy
view stays near-monochrome.

#### Scenario: A verdict is legible without colour perception

- **WHEN** a ranked health view is rendered
- **THEN** each row's verdict is readable as text, and removing colour removes no
  information

#### Scenario: A healthy cluster is quiet

- **WHEN** every queue in a cluster is healthy
- **THEN** the view carries no warning or danger colour

#### Scenario: An uncomputed verdict is distinguishable from a healthy one

- **WHEN** a queue's verdict could not be computed
- **THEN** the row states that, and is visually distinct from a row reporting health
