## ADDED Requirements

### Requirement: Installation-wide jobs run once across instances

Every scheduled job SHALL declare its scope: every instance, or once per installation. When several instances share one database, an installation-wide job SHALL run on at most one instance per tick. An instance that finds the job held elsewhere SHALL record the tick as skipped elsewhere, and the job SHALL NOT be reported as degraded for that. If the holder crashes, the job SHALL resume on another instance within one minute, or within one of the job's own intervals when that is longer.

#### Scenario: Two instances, one housekeeping run
- **WHEN** two instances share one database and an installation-wide job is due
- **THEN** it runs on exactly one of them and the other records the tick as skipped elsewhere

#### Scenario: An instance-scoped job runs everywhere
- **WHEN** an instance-scoped job is due on two instances
- **THEN** it runs on both

#### Scenario: A crashed holder does not stall the job
- **WHEN** the instance running an installation-wide job dies mid-run
- **THEN** another instance runs the job once the lock lapses, within one minute or one interval, whichever is longer
