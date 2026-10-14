## MODIFIED Requirements

### Requirement: Every background job reports its status

The system SHALL record, for every scheduled background job of every enabled feature: when it last started, when it last finished, its last error, how many times it has run and failed, and when it is next due. It SHALL also count the runs that timed out, the runs that lost their lock, the runs that started later than scheduled because the previous one overran, and whether the job's schedule is invalid. The system SHALL expose this as a read that requires the settings-read permission. Each job's run duration SHALL also be published as a metric labelled with the job and the owning feature, and the timed-out, lock-lost and overrun counts as metrics.

#### Scenario: A job's last failure is visible

- **WHEN** a background job's most recent run throws
- **THEN** the job status read reports that run's error, its time, and an incremented failure count

#### Scenario: Job status requires permission

- **WHEN** a caller without the settings-read permission requests job status
- **THEN** the request is refused

#### Scenario: A timeout is counted

- **WHEN** a run is interrupted for exceeding its timeout
- **THEN** the job status shows the run as timed out and the timed-out count rises by one

#### Scenario: An invalid schedule is shown

- **WHEN** a job's schedule cannot be parsed
- **THEN** the job status shows it as having an invalid schedule

### Requirement: Installation-wide jobs run once across instances

Every scheduled job SHALL declare its scope: every instance, or once per installation. When several instances share one database, an installation-wide job SHALL run on at most one instance per tick. An instance that finds the job held elsewhere SHALL record the tick as skipped elsewhere, and the job SHALL NOT be reported as degraded for that. If the holder crashes, the job SHALL resume on another instance within one minute, or within one of the job's own intervals when that is longer. The holder SHALL extend its lock while it runs, and a run that loses its lock, because the extension failed, SHALL be interrupted and counted as lock lost.

#### Scenario: Two instances, one housekeeping run
- **WHEN** two instances share one database and an installation-wide job is due
- **THEN** it runs on exactly one of them and the other records the tick as skipped elsewhere

#### Scenario: An instance-scoped job runs everywhere
- **WHEN** an instance-scoped job is due on two instances
- **THEN** it runs on both

#### Scenario: A crashed holder does not stall the job
- **WHEN** the instance running an installation-wide job dies mid-run
- **THEN** another instance runs the job once the lock lapses, within one minute or one interval, whichever is longer

#### Scenario: A run loses its lock
- **WHEN** a running installation-wide job can no longer extend its lock
- **THEN** the run is interrupted and counted as lock lost, so that two instances never run it at once

## ADDED Requirements

### Requirement: A job never overlaps itself and is bounded in time
A job SHALL NOT start while its previous run on the same instance is still running. The next run SHALL be scheduled after the previous one completes. Every job SHALL have a timeout, and a run that exceeds it SHALL be interrupted and recorded as timed out. A job whose schedule cannot be parsed SHALL be marked degraded with the cause named, SHALL NOT stop other jobs, and SHALL recover without a restart once the schedule is fixed. Cron schedules SHALL be evaluated in UTC. The first failure and the recovery of a job SHALL be logged, and not every repeat.

#### Scenario: A run takes longer than its interval
- **WHEN** a fixed-delay job's run lasts longer than its delay
- **THEN** the next run starts after that run completes and the two never run together

#### Scenario: A run hangs
- **WHEN** a run exceeds its timeout
- **THEN** it is interrupted, recorded as timed out, and the job runs again on its next schedule

#### Scenario: A schedule is malformed
- **WHEN** a job's cron expression is invalid
- **THEN** the job is reported degraded with an invalid schedule, other jobs keep running, and it runs again once the expression is corrected

#### Scenario: A schedule is read in UTC
- **WHEN** a cron job is due at a stated hour
- **THEN** the hour is in UTC whatever the server's time zone

#### Scenario: A job keeps failing
- **WHEN** a job fails on every run
- **THEN** the first failure is logged once, and the recovery is logged when it succeeds

### Requirement: No thread outlives shutdown
When Studio stops, every thread it started, including job, scrape, plugin and event-stream threads, SHALL end. No Studio thread SHALL keep the process alive. Shutdown SHALL stop new runs, wait for running ones, then interrupt those still running.

#### Scenario: Studio stops after use
- **WHEN** Studio has served an event stream, run a job and loaded a plugin, and then stops
- **THEN** no thread named for Studio and no non-daemon thread outside the JVM's own is alive

#### Scenario: A run is interrupted at shutdown
- **WHEN** a job is still running when the wait for running jobs ends
- **THEN** it is interrupted and its run is recorded as interrupted

### Requirement: Heavy work cannot exhaust the connection pool
Work that holds a database connection for long, such as exports, previews, history reads and the SQL console, SHALL be limited so that it cannot take every connection. An installation-wide cap on such work SHALL leave connections for ordinary requests and background jobs.

#### Scenario: Many heavy requests arrive
- **WHEN** many users start heavy requests at once
- **THEN** the extra ones are refused at the cap and ordinary requests still get a connection

#### Scenario: Heavy work ends
- **WHEN** a heavy request finishes or its client goes away
- **THEN** its connection and its share of the cap are released
