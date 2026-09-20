## ADDED Requirements

### Requirement: A consumer-health rule evaluates the shared verdict, not a raw metric

The system SHALL offer a rule kind whose condition is a queue's consumer-health verdict,
evaluated from the same classification the console and the agent surface report, compared
against a severity the operator selects. It SHALL NOT reimplement the classification.

#### Scenario: A rule fires on a severity, not a number an operator must decode

- **WHEN** an operator creates a consumer-health rule
- **THEN** the condition is expressed as a named verdict severity, not as a bare numeric
  threshold

#### Scenario: A queue reaching the selected severity fires

- **WHEN** a queue's verdict reaches or exceeds the severity a rule selects, for the
  rule's debounce duration
- **THEN** that rule fires for that queue, carrying the verdict and its evidence

#### Scenario: A recovering queue resolves

- **WHEN** a firing queue's verdict falls below the rule's selected severity
- **THEN** the firing resolves through the existing resolution path

#### Scenario: An uncomputable verdict never fires and never resolves

- **WHEN** a queue's verdict is `INSUFFICIENT_DATA`
- **THEN** it is excluded from the rule's evaluation entirely, so it neither fires nor
  resolves a firing that is still true

#### Scenario: A paused queue does not page

- **WHEN** a queue is paused and therefore holds a backlog by design
- **THEN** it does not fire a consumer-health rule
