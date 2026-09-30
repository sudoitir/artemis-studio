## ADDED Requirements

### Requirement: A setting can declare bounds
A setting SHALL be able to declare a minimum and a maximum. A value outside them SHALL be rejected with
the allowed range, leave no audit row, and change nothing. A duration setting SHALL accept `forever` only
when its maximum is `forever`.

#### Scenario: Below the minimum
- **WHEN** a value below a setting's minimum is written
- **THEN** the write is rejected with the allowed range and the stored value is unchanged

#### Scenario: Forever
- **WHEN** `forever` is written to a duration setting whose maximum is not `forever`
- **THEN** it is rejected
