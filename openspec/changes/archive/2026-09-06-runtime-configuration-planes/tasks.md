# Tasks

## Plane A — operator settings

- [x] Rewrite `SettingsService` around a `SettingKey` registry (group, label, hint,
      kind, default supplier, optional apply)
- [x] Audit `put` / `reset` in the command transaction; validate before the audit row
- [x] Replace per-key `findById` with one `volatile` override snapshot
- [x] Add the 17 new keys and their `application.yml` / `ArtemisStudioProperties`
      defaults (`rr.sample-interval`, four crons, `sse.heartbeat-interval`)
- [x] Add runtime setters: `RrFlowReaper.setRetentionDays`,
      `RrCorrelator.setDefaultDeadlineMs` / `setPayloadCaptureBytes`,
      `BrokerClientFactory.setTimeouts`
- [x] Point `AlertDispatcher` at `SettingsService` for attempts and backoff
- [x] Remove the dead `tierAMillis` / `tierBMillis` / `tierCMillis` SpEL hooks

## Schedules

- [x] Extract `DynamicTriggers.fixedDelay` / `.cron`; have `ScrapeScheduler` use it
- [x] Add `DynamicSchedules` and move nine tasks onto trigger tasks
- [x] Remove `@Scheduled` from each moved method (annotation + trigger would double-run)
- [x] Reject a cron that fires more than once a minute, and a malformed one

## Plane B — deploy-time properties

- [x] Spike: Spring Cloud 2025.1.3 + `spring-cloud-starter-bootstrap` on Boot 4.1.0 —
      context starts, `bootstrap.yml` loads, `ContextRefresher` present
- [x] Changeset `016-studio-config-property.sql` + master changelog include
- [x] `JdbcConfigPropertySourceLocator` + `spring.factories` bootstrap registration
- [x] `bootstrap.yml` with datasource coordinates and `encrypt.key`
- [x] Expose `/actuator/refresh`; gate it on `settings:write` via `PermissionResolver`
- [x] Keep the loaded source below the environment (flags emitted by the locator, not
      `bootstrap.yml`), verified on a real boot in both directions

## Frontend

- [x] Render the settings form from the API response; delete the `FIELDS` array
- [x] Regenerate `openapi.json` (snapshot test) and `schema.d.ts`

## Truthfulness

- [x] Remove "takes effect on restart" from `SettingsService`, `ArtemisStudioProperties`,
      `application.yml` and the Settings screen
- [x] Fix `RrSampler` / `RrDeadlineSweep` ignoring `rr.sweep-interval`

## Tests

- [x] `SettingsServiceTest`: kind round-trip, cron rejection, form metadata, audit rows,
      no audit row for a rejected value
- [x] `DynamicTriggersTest`: schedule re-read per fire, for both helpers
- [x] `JdbcConfigPropertySourceLocatorTest`: load, profile precedence, and that a missing
      table or unreachable database never fails startup
- [x] `EndpointProtectionTest`: `/actuator/refresh` rejects an unauthenticated request
- [x] `OperationalConfig.test.tsx`: an unknown key still renders from its server description
- [x] `Props` test helper so a new properties section is one edit, not a dozen

## Process

- [x] ADR-0047, ADR-0048, index rows
- [x] CHANGELOG `[Unreleased]`, README env-var row
- [x] `./mvnw verify` and `just verify-web` green
- [x] Archive this change
