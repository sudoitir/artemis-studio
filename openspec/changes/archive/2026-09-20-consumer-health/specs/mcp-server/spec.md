## ADDED Requirements

### Requirement: Queue diagnosis reports the same verdict as the console

The agent surface's queue diagnosis SHALL report the shared consumer-health verdict and
its evidence as structured values, rather than deriving its own trend or slow-consumer
judgement. A verdict that could not be computed SHALL be reported as such.

#### Scenario: A trend is a value, not a sentence

- **WHEN** an agent diagnoses a queue
- **THEN** the depth trend is returned as a direction and a rate, not as prose a model must
  parse

#### Scenario: The agent reports the broker's verdict where there is one

- **WHEN** the broker has reported a slow consumer on the diagnosed queue
- **THEN** the diagnosis reports that verdict and the named consumer, in preference to a
  derived one

#### Scenario: Insufficient sampling is stated

- **WHEN** an agent diagnoses a queue with too few samples to derive a rate
- **THEN** the diagnosis states that the verdict could not be computed, rather than
  reporting the queue as healthy
