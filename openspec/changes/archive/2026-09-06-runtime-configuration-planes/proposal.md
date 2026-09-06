## Why

The ask was "add a config server so settings change from the panel without a
restart, add more settings, use `bootstrap.yml`, seed config on first startup,
back it with Postgres". Investigation found most of the mechanism already exists
and one part of the premise is false.

`studio_setting` (changeset 009) is already a Postgres-backed runtime override
layer with an API and a Settings screen, and ADR-0025 already made scrape cadence
live. What is actually wrong:

1. **The product lies about itself.** `SettingsService`'s javadoc and every hint on
   the Settings screen still say "takes effect on restart", months after ADR-0025
   made it untrue. The complaint that prompted this is real; its cause is stale
   text.
2. **`artemis-studio.rr.sweep-interval` is dead.** `RrDeadlineSweep` and `RrSampler`
   hardcode `@Scheduled(fixedDelay = 5000)` and ignore it. A documented, bound
   property does nothing.
3. **Settings writes are not audited** — the one mutating path that violates
   non-negotiable #3.
4. **Only 8 keys are tunable and 5 reach the UI.** Broker timeouts, all `rr.*` and
   `alerting.*` values, and every reaper cron are frozen at startup. Adding a key
   means edits in four places plus a hardcoded `FIELDS` array in the frontend.

On the config server itself: there is no Spring Cloud train for Boot 4.1
(`2025.1.3` pins Boot 4.0.8), an app cannot `spring.config.import` from its own
un-opened port, and `@RefreshScope` cannot re-arm a `@Scheduled` cadence. What
Spring Cloud does add that is missing is the bootstrap phase. See ADR-0047.

"Seed config on first startup" is deliberately **not** implemented: an absent row
already means "use the packaged default", which is strictly better, because seeded
rows would freeze the defaults of whichever version started first and silently
ignore improved defaults on upgrade.

## What Changes

**Plane A — the operator plane (`studio_setting`).**

- `SettingsService` becomes a registry: one `register(...)` line per key carries its
  group, label, hint, kind, packaged default and how it applies. `effective()`,
  validation, `put`/`reset` and the boot-time apply all iterate it.
- Settings writes are audited in the same transaction, with old and new value.
  Validation runs before the audit row, so a rejected value records nothing rather
  than recording a change that then rolls back.
- Overrides are cached in one `volatile` map refreshed on write, replacing a
  `SELECT` per key per read (triggers read these on every fire).
- 17 new tunable keys: broker connect/read timeout; `rr` default deadline, payload
  capture cap, sweep interval, sampler interval, retention days, reaper cron;
  `alerting` dispatch interval, max attempts, initial and max backoff; `events`
  flush and reaper cron; `metric` reaper and partition-maintainer cron; SSE
  heartbeat interval.
- `GET /api/v1/settings` returns group/label/hint/kind per key and the Settings
  screen renders from that, deleting the hardcoded `FIELDS` array.

**Schedules (ADR-0048).** `DynamicTriggers` (shared `fixedDelay` / `cron` helpers,
extracted from `ScrapeScheduler`) and `DynamicSchedules` register nine tasks as
trigger tasks that re-read their schedule per fire. The `@Scheduled` annotation is
removed from each. `NodeCallLimiter.refill()` stays annotated — one second is part
of the unit "permits per second", not a knob.

**Plane B — the deploy-time plane.** `spring-cloud-starter-bootstrap` (no config
server), a `bootstrap.yml`, `studio_config_property` (changeset 016) read by a
`PropertySourceLocator` over plain JDBC before the main context, `{cipher}`
decryption via `ARTEMIS_STUDIO_CONFIG_ENCRYPT_KEY`, and `POST /actuator/refresh`
gated on `settings:write`.

## Impact

- **Behaviour change:** `rr.sweep-interval` now applies. A deployment that set it
  and saw no effect will see the configured value take hold.
- **Not backward compatible by design:** the dead `tierAMillis()`/`tierBMillis()`/
  `tierCMillis()` SpEL entry points are removed, and `AlertDispatcher` now takes
  `SettingsService` instead of `ArtemisStudioProperties`.
- New Spring Cloud BOM on a train built for Boot 4.0.8 — accepted only after a
  spike proved the context starts, `bootstrap.yml` loads and the refresh machinery
  wires up. Exit path recorded in ADR-0047.
- `/actuator/refresh` cannot rebind the ~18 singletons holding the immutable
  properties record. Documented, not papered over.
- Specs: `studio-settings` requirements are rewritten around the registry and gain
  audit, form-metadata and deploy-time-plane requirements.
- ADRs: 0047 (two planes), 0048 (dynamic schedules, extends 0025).
