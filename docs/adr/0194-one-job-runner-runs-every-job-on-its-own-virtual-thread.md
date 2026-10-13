# ADR-0194: One job runner runs every job on its own virtual thread

- **Status**: accepted
- **Date**: 2026-10-13
- **Deciders**: Mahdi Amirabdollahi

## Context

Jobs ran on three schedulers: Spring's `SchedulingConfigurer` for the modules' jobs
([ADR-0048](0048-settings-driven-dynamic-schedules.md)), a `ThreadPoolTaskScheduler` of four threads
for the scrape tiers, and another for the plugins' jobs. Three things were wrong.

- **A job overlapped itself.** With virtual threads on, Spring Boot configures a
  `SimpleAsyncTaskScheduler`. It ignores a pool size and starts a new thread for every trigger run, so a
  fixed delay is measured from the dispatch, and a per-instance job slower than its delay ran on top of
  itself.
- **A job could hang for good.** Nothing bounded a run, and nothing told an operator that one had stalled
  other than the three-intervals health rule.
- **Three pools, two of them non-daemon.** The scrape and plugin pools kept the process alive after the
  context closed, and a malformed cron stopped its job with no sign.

Installation-wide jobs ([ADR-0125](0125-installation-wide-jobs-run-once-through-shedlock.md)) kept their
lock alive through ShedLock's `KeepAliveLockProvider`, which extends the lock on a thread of its own and
cannot tell the run that it failed.

## Decision

**We will run every `ScheduledJob` (the modules', the scrape tiers' and the plugins') on one `JobRunner`.**

- One daemon platform thread, `jobs-timer`, holds the timers and does nothing else. Each run gets a
  virtual thread named `job-<id>`, with the host class loader as its context loader (a plugin's task
  switches to its own for the run and back).
- The next fire is computed from the job's `Trigger` only after the run ends, so a fixed delay is real
  and a job never overlaps itself. The settings behind `DynamicTriggers` are re-read at every fire, so a
  cadence change applies without a restart ([ADR-0025](0025-live-scrape-cadence-scheduling-configurer.md),
  ADR-0048 stands in that respect). A run that outlasts the time its next run was due counts in
  `studio.job.overruns`.
- **Every run has a timeout.** `ScheduledJob.timeout()` defaults to the larger of five minutes and three
  intervals, or an hour for a cron, and `withTimeout` sets another. At the timeout the run is
  interrupted and recorded as timed out (`studio.job.timeouts`), and the job does not run again until
  that thread has ended.
- **The runner extends the lock itself.** An installation-wide run takes its ShedLock lock
  (`JdbcTemplateLockProvider` with database time, as before), and a virtual thread extends it every half
  of `lockAtMostFor` through `SimpleLock.extend`. An extension that fails or finds the lock gone
  interrupts the run and records it as lock lost (`studio.job.lock_lost`); a lost lock is not unlocked, as
  another instance may hold it. `KeepAliveLockProvider` and its executor are removed. There are no fencing
  tokens ([ADR-0152](0152-replicas-coordinate-through-postgres.md) stands): interruption plus the work's
  own idempotency (conditional updates, `SKIP LOCKED`, `IF NOT EXISTS`) make a rare double run harmless.
- **A malformed cron marks the job `invalidSchedule`,** shown as degraded with the cause. The runner
  asks again every minute, so a corrected setting runs again without a restart, and other jobs are
  untouched.
- **Cron schedules are read in UTC,** whatever the server's zone.
- **Only the first failure and the recovery are logged,** not every repeat. The status keeps the last error.
- **Shutdown pauses, waits, then interrupts.** The jobs shutdown step stops every timer, waits ten seconds
  for runs in progress, then interrupts the rest, each recorded as interrupted. The scrape tiers' own stop
  step is gone: they are jobs like the rest.
- The job status API gains `lastOutcome`, `timeouts`, `lockLost`, `overruns` and `invalidSchedule`.

### The installation-wide jobs

This replaces the list in ADR-0125, which named jobs that no longer exist. These run on one instance per
tick (`ScheduledJob.Scope.INSTALLATION`):

| Job | Module |
| --- | --- |
| `approval-expiry`, `approval-runner` | approval |
| `audit-delivery` | audit feed |
| `bulk-recovery` | bulk |
| `governance-remask` | governance |
| `housekeeping`, `storage-sample` | lifecycle |
| `message-index-partitions` | sql |
| `metric-partitions`, `scrape-discovery`, `scrape-tier-c` | scrape |
| `plugin-identity-revalidation` | plugins |
| `rr-deadline-sweep` | request-reply |
| `secret-rotation-sweep` | settings |
| `transfer-recovery` | transfer |

Every other job is `INSTANCE`. That includes scrape tiers A and B, which feed this instance's live
stream, Core subscriptions and plugin metric sources. A plugin states the scope of each job it declares.

## Consequences

- A slow job holds only its own thread. No pool size caps how many jobs run, and a hung job is interrupted.
- A job that fails every run, or whose cron is wrong, is visible in status and health, and the log is not
  flooded.
- `ScheduledJob` gains a `timeout` component, and `JobStatuses` loses `instrument`, `trigger`, `pause` and
  `resume`, which are the runner's now: a plugin that built `ScheduledJob` by hand must pass a timeout.
- A run that ignores interruption keeps its job from running again. That is the cost of never
  overlapping; the timeout counter and the lag gauge show it.
- A cron that fired in the server's zone now fires in UTC, which is a change for an operator who set an
  hour assuming local time.
- No thread outlives the context: `ThreadLeakIntegrationTest` starts the whole application, runs a job,
  stops it, and fails on any surviving thread named for Studio or any new non-daemon thread.

## Alternatives considered

- **db-scheduler.** It covers installation-wide jobs only, so it would mean two job systems next to the
  runner and ShedLock. Rejected.
- **Keep Spring's scheduler and bound each run from inside the job.** Spring's scheduler offers no timeout
  or hook between a run and its next fire, and the overlap lives in the scheduler itself.
- **Keep `KeepAliveLockProvider`.** It extends the lock on a shared thread and cannot interrupt the run it
  protects, so a run that lost its lock kept going beside another instance's.
- **Fencing tokens.** Every written row would need one. ADR-0152 already decided against it.
