## MODIFIED Requirements

### Requirement: Operational settings are stored and overridable at runtime

The system SHALL persist a set of operational settings — the three scrape tier
intervals, the per-node management-call ceiling, the metric retention window, and
the bulk-operation safety cap — and SHALL use the stored value when present,
falling back to the packaged configuration default otherwise. A changed setting
SHALL take effect without a restart, by the next relevant scheduled run or the
next request that reads it. The bulk safety cap SHALL default to 1000.

#### Scenario: Default until overridden

- **WHEN** no stored value exists for a setting
- **THEN** the system uses the packaged configuration default and reports that
  value as the current setting

#### Scenario: Change takes effect on the next run

- **WHEN** an operator changes the metric retention window
- **THEN** the next retention run trims to the new window without a restart

#### Scenario: Bulk cap change takes effect on the next mutation

- **WHEN** an operator lowers the bulk safety cap
- **THEN** the next bulk mutation is evaluated against the new cap without a
  restart

#### Scenario: Invalid value is rejected

- **WHEN** an operator submits a non-positive interval, ceiling, or cap
- **THEN** the update is rejected with a validation message and the previous
  value is kept
