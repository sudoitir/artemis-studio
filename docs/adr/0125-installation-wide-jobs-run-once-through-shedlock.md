# ADR-0125: Installation-wide jobs run once, through ShedLock

- **Status**: accepted
- **Date**: 2026-09-28
- **Deciders**: Mahdi Amirabdollahi

## Context

Studio supports several instances on one PostgreSQL. Sessions are shared
([ADR-0123](0123-revoking-access-ends-sessions.md)), and the broker-heavy per-cluster jobs already
coordinate through Postgres advisory locks (`ClusterLock`: drift, setup review, flow sampling,
plugin messaging). The rest of the `ScheduledJob`s ([ADR-0048](0048-settings-driven-dynamic-schedules.md))
simply ran on every instance. For work on shared state that is waste at best:

- housekeeping deleted the same rows N times;
- the deadline sweep could move the same request-reply flow twice and write two events for it;
- partition maintenance ran its DDL concurrently from every instance.

Plugins had the same problem and no way to say otherwise.

## Decision

- Every `ScheduledJob` declares a `Scope`, and there is no default:
  - `INSTALLATION`: a job whose effect lies only in the shared database or on the brokers. It
    runs on one instance per tick.
  - `INSTANCE`: a job that drains, refills or reconciles this instance's own memory, streams or
    consumers, or that already coordinates per cluster. It runs everywhere.
- `JobStatuses.instrument`, which every scheduler (built-in, scrape tiers, plugin bridge) already
  wraps each run with, runs an `INSTALLATION` job through ShedLock's `DefaultLockingTaskExecutor`:
  - `KeepAliveLockProvider` over `JdbcTemplateLockProvider` with database time, on the `shedlock`
    table (changeset `kernel-jobs 0001`);
  - the lock name is the job id;
  - `lockAtLeastFor` is the job's `minimumGap`: 90% of a fixed delay, at most five minutes, or 30
    seconds for a cron;
  - `lockAtMostFor` is the larger of 60 seconds and that gap, extended while the run lasts.
- A tick that finds the lock held elsewhere is recorded as `lastSkippedElsewhere`. The job status
  API reports it together with the scope, and a skipped tick counts as a completion when judging
  a stalled job.
- Built-in classification:
  - **INSTALLATION**: `bulk-preview-housekeeping`, `transfer-preview-housekeeping`,
    `events-reaper`, `rr-flow-reaper`, `rr-deadline-sweep`, `message-index-partitions`,
    `metric-reaper`, `metric-partitions`, `scrape-tier-c`, `scrape-discovery`.
  - **INSTANCE**: everything else. That includes scrape tiers A and B, which feed this instance's
    live stream, Core subscriptions and plugin metric sources.
- The plugin contract moves to 4, because `ScheduledJob`'s factories changed.

## Consequences

- Housekeeping, sweeps and partition DDL happen once per tick however many instances run.
- A crashed holder blocks a job for at most `lockAtMostFor`: one minute, or one gap for jobs whose
  interval is longer than that.
- An installation-wide job's instance-local side effects (an SSE nudge) reach only the instance
  that ran it. That matches the existing per-instance SSE fan-out (ADR-0018).
- Every plugin is rebuilt against contract 4 and states each job's scope.

## Alternatives considered

- **Hand-written advisory locks per job.** `ClusterLock` exists, but it is per cluster and gives no
  minimum gap, keep-alive or crash expiry. ShedLock is the maintained, standard answer.
- **One leader instance for all jobs.** It concentrates every job on one node, needs election and
  failover of its own, and still needs per-job scope for the instance-local ones.
- **A default scope.** A silent `INSTANCE` default would keep the bug for every job nobody looked
  at, and a silent `INSTALLATION` default would break the instance-local ones.
