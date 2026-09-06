# ADR-0048: Every settings-tunable schedule is a trigger task, not a `@Scheduled` annotation

- **Status**: accepted
- **Date**: 2026-09-06
- **Deciders**: maintainers

## Context

ADR-0025 replaced the scrape tiers' SpEL-bound `@Scheduled` methods with a
`SchedulingConfigurer` registering trigger tasks that re-read `SettingsService`
when computing each next run, so a cadence change applies without a restart. It
scoped that to the three tiers, because they were the only cadences that were
settings-tunable at the time.

ADR-0047 makes many more schedules tunable, and the audit that preceded it found
what a per-case decision produces over eight phases:

- `RrSampler` and `RrDeadlineSweep` both hardcoded `@Scheduled(fixedDelay = 5000)`
  and **ignored `artemis-studio.rr.sweep-interval` entirely**. The property was
  documented, bound, and dead. Changing it did nothing, silently.
- `BrokerEventWriter` and `AlertDispatcher` used `fixedDelayString` placeholders,
  which resolve once at wiring time — configurable per deployment, not at runtime.
- `SseHub`'s heartbeat and four reaper crons were literals, despite the heartbeat
  being a property of whichever proxy sits in front of Studio, which the image
  cannot know.

The common failure is not any one of these. It is that an annotation's schedule is
resolved once, so the value an operator reads in Settings and the value the
scheduler is actually using can disagree with nothing to show for it. That is the
same class of bug as the stale "takes effect on restart" hint ADR-0047 removes:
the system is lying about itself, plausibly, in a way that only shows up as
"changing this does nothing".

## Decision

**We will register every settings-tunable schedule as a `SchedulingConfigurer`
trigger task that re-reads its schedule on each fire, and remove the `@Scheduled`
annotation from the method.**

- `DynamicTriggers` holds the two helpers — `fixedDelay(Supplier<Duration>)` and
  `cron(Supplier<String>)` — extracted from `ScrapeScheduler` so both schedulers
  share one implementation. Fixed-delay semantics are preserved exactly: the gap is
  measured from the previous run's completion, so a slow task never overlaps itself.
- `DynamicSchedules` owns *when* the non-scrape tasks run; the task classes keep
  *what* they do. The annotation is **removed**, not disabled — an annotation and a
  trigger for the same method would run it twice.
- `ScrapeScheduler` keeps its own registration and its own task scheduler: a slow
  reaper must not delay a scrape tier, and a slow tier must not delay a reaper.
- **`@Scheduled` with a literal remains correct for a genuinely fixed cadence.**
  `NodeCallLimiter.refill()` stays annotated at one second, because one second is
  what "permits per *second*" means — it is part of the unit, not a tuning knob.
- A cron is validated on write and rejected if it would fire more than once a
  minute. These schedules drive bulk `DELETE`s and DDL; a stray seconds field
  turning a nightly trim into a per-second one has to fail where it is typed. A
  malformed expression stops the task rather than falling back to a schedule nobody
  chose — a reaper silently running on the wrong cron is worse than one that
  visibly stops.

## Consequences

- Every cadence and cron in Settings is honoured on the next fire. The three
  properties that were silently dead are now live, which is a behaviour change for
  anyone who had set `rr.sweep-interval` and concluded it did nothing.
- Schedules are now defined in a different file from the code they run. That split
  is the cost; `DynamicSchedules` is one screen long and lists every task, which is
  a better index than nine annotations spread across nine packages.
- `DynamicTriggersTest` pins the property that matters — that the schedule is
  re-read per fire. If that regresses, every cadence setting quietly reverts to
  needing a restart while still looking correct in the UI, so it is worth a test of
  its own rather than being implied by the scheduler tests.
- A task that throws still relies on Spring's default behaviour. Nothing here
  changes error handling.

## Alternatives considered

- **Keep `@Scheduled(fixedDelayString = "#{@settingsService...}")`.** This is what
  ADR-0025 already rejected: SpEL in a scheduling attribute is evaluated once, so it
  reads as dynamic and behaves as static. It is precisely the trap being removed.
- **One `SchedulingConfigurer` for everything, tiers included.** Rejected: the tiers
  need an isolated pool. Merging them would let a nightly partition drop delay a
  five-second HA poll.
- **Re-register tasks on change instead of re-reading per fire.** More moving parts
  (cancellation, races with a running task) for a value read a few times a minute.
  Re-reading in the trigger is free at these frequencies.
- **Cache the parsed `CronExpression`.** Would need invalidation for the one thing
  the parse exists to support. These fire a few times a day.
