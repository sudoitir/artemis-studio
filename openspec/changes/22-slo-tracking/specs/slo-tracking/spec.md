## ADDED Requirements

### Requirement: An SLO is defined on a supported indicator
The system SHALL let a user define an SLO on queue depth, message age, consumption lag or request-reply latency for a set of subjects, with a target and a window, and SHALL validate the definition before saving.

#### Scenario: A valid SLO is saved
- **WHEN** a user sets 99 percent of samples with message age under 30 seconds over 30 days
- **THEN** the SLO is stored and starts to be evaluated

#### Scenario: An indicator without data
- **WHEN** the chosen indicator has no source for the subject
- **THEN** the definition is refused with the reason

### Requirement: Attainment and error budget are computed over the window
For each SLO the system SHALL compute attainment and the remaining error budget over its window, and SHALL state when the history is shorter than the window.

#### Scenario: Budget is spent
- **WHEN** samples outside the objective accumulate
- **THEN** the remaining budget falls and the dashboard shows the percentage left

#### Scenario: New SLO
- **WHEN** less history exists than the window
- **THEN** the value is shown as partial with the covered period

### Requirement: Burn rate alerts use several windows
The system SHALL raise alerts when the budget burns faster than allowed over a long and a short window together, through the alerting engine, so routing, silences and escalation apply.

#### Scenario: A fast burn
- **WHEN** budget burns fast over both windows
- **THEN** an alert fires and is routed by the alerting engine

#### Scenario: A short spike
- **WHEN** only the short window shows fast burn
- **THEN** no alert fires

### Requirement: An SLO dashboard shows objectives at a glance
The interface SHALL show every SLO with its target, current attainment, remaining budget and burn rate, and link to the subject and to its alerts. Users SHALL only see SLOs for clusters they may read.

#### Scenario: An operator opens the dashboard
- **WHEN** SLOs exist for several clusters
- **THEN** each one shows status and budget, and SLOs of unreadable clusters are absent
