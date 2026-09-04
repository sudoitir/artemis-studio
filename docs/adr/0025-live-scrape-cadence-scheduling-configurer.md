# ADR-0025: Scrape cadence applies without a restart, via `SchedulingConfigurer`

- **Status**: accepted
- **Date**: 2026-09-04
- **Deciders**: Mahdi Amirabdollahi

## Context

ADR-0015 and the `studio-settings` spec both promise that changing a scrape tier
interval "takes effect without a restart, by the next relevant scheduled run".
The Phase 2 implementation did not deliver that. `ScrapeScheduler` binds each
tier's cadence with SpEL against the settings bean:

```java
@Scheduled(fixedDelayString = "#{@settingsService.tierAMillis()}",
           initialDelayString = "#{@settingsService.tierAMillis()}")
public void tierA() { ... }
```

Spring resolves `fixedDelayString` once, when the scheduled method is registered
at startup. A later `PUT /api/v1/settings` updates the stored value, but the
scheduler keeps firing at the old interval until the process restarts.
`SettingsService`'s javadoc admits this ("cadence changes take effect only on
restart; limiter and reaper apply immediately"), and Phase 2 filed the fix as a
fast-follow.

## Decision

We will replace the three SpEL-bound `@Scheduled` methods with a
`SchedulingConfigurer` that registers each tier as a trigger task whose interval
is read from `SettingsService` on every computation of the next run.

- `ScrapeScheduler implements SchedulingConfigurer`. In `configureTasks`, it
  calls `registrar.addTriggerTask(runnable, trigger)` once per tier.
- Each `Trigger.nextExecution(context)` returns
  `lastCompletion + settingsService.tier{A,B,C}Millis()` (falling back to the
  scheduled start when there is no last completion), so a changed setting is
  honoured on the very next scheduling decision — no restart, no re-registration.
- The existing pooled `TaskScheduler` (`spring.task.scheduling.pool.size`,
  prefix `scrape-`) is reused; the trigger tasks run on it exactly as the
  annotated methods did.
- Tier bodies are unchanged: still one batched Jolokia POST per node per tick,
  still `runIsolated` per node, still a short `@Transactional` persist after the
  I/O (ADR-0015).
- `SettingsService`'s "restart to apply" javadoc caveat is removed, and the
  matching caption is removed from the settings screen.

## Consequences

- The product finally matches the spec and ADR-0015: an operator dials a cadence
  in Settings and the next cycle uses it.
- `SettingsService.tier{A,B,C}Millis()` is now called once per tier per cycle
  instead of once at startup. These are a `studio_setting` lookup with a config
  fallback — cheap, and already called on the settings read path — but they are
  now on the hot path and must stay cheap (no broker call, no heavy query).
- `@Scheduled` no longer appears on the tier methods; anyone reading the
  scheduler has to know the triggers are registered in `configureTasks`. A class
  comment points there.
- Other `@Scheduled` beans (`NodeCallLimiter.refill`, `SseHub.heartbeat`,
  `MetricSampleReaper.reap`) are unaffected — their cadences are genuinely fixed.

## Alternatives considered

- **Keep `@Scheduled` and restart the scheduler on a settings change.** Rejected
  — tearing down and re-registering scheduled tasks from a REST call is fragile
  (races with an in-flight tick, needs a handle to the registrar) and heavier
  than a trigger that just reads the current value.
- **Poll a shorter fixed interval and skip ticks to simulate a slower cadence.**
  Rejected — wastes wakeups, and "skip 4 of every 5" is a worse approximation
  than just computing the real next time.
- **A custom `@Scheduled`-like annotation reading settings.** Rejected —
  `SchedulingConfigurer` + `Trigger` is the framework's own answer to exactly
  this; no need to build one.
