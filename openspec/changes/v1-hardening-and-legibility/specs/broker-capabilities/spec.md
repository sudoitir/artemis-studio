## ADDED Requirements

### Requirement: Native slow-consumer detection is reported three-state with its snippet

The system SHALL report whether the broker's own slow-consumer detection is configured,
using the same three-state grammar as every other capability: configured, not configured,
or unknown. When the broker's management surface does not expose the slow-consumer
threshold at all, the state SHALL be **unknown** — never "not configured" — because
Studio cannot tell the difference, and reporting the difference it cannot observe would
be a guess.

Where the state is not "configured", the result SHALL include the exact `broker.xml`
snippet that enables native slow-consumer detection, including the threshold, the check
period, and the policy.

#### Scenario: Threshold not exposed

- **WHEN** the broker's address-settings read does not return a slow-consumer threshold
- **THEN** native slow-consumer detection is reported as unknown, with the enabling
  `broker.xml` snippet

#### Scenario: Detection configured

- **WHEN** the broker returns a slow-consumer threshold, check period, and policy
- **THEN** native slow-consumer detection is reported as configured, with those values

#### Scenario: Detection off

- **WHEN** the broker exposes the slow-consumer threshold and reports it disabled
- **THEN** native slow-consumer detection is reported as not configured, with the
  enabling `broker.xml` snippet
