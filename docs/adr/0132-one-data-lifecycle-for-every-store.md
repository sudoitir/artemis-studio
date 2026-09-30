# ADR-0132: One data lifecycle for every store

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Mahdi Amirabdollahi

## Context

Retention lived in eight module-owned reapers and partition maintainers. Each had its own settings
keys and deleted with one unbounded statement. Several tables that grow with use had no retention
at all: audit, alert history, finished bulk and transfer runs, broker configuration history, setup
reviews and classification findings. Operators could not see what a store held, preview a policy,
set a quota or watch table health. Plugins that keep data had nothing to join.

## Decision

- **One SPI for every store.** `kernel/lifecycle` defines the `@PluginApi` types
  `HousekeepingContributor`, `ManagedStore`, `StoreDef`, `StoreUsage` and `PurgeEstimate`.
  - A module or a plugin contributes `ManagedStore`s. Each declares its tables and its retention
    bounds, and reports usage.
  - Each store previews a cutoff without deleting, and purges one bounded batch per call.
  - Plugin stores arrive through `HousekeepingPluginBridge`, with ids prefixed `<pluginId>.`.
- **Policies are settings.** Each store has `lifecycle.<store>.retention`, `.quota` and
  `.quota-warn-percent`, registered at runtime through `SettingsService.addSettings(namespace,
  defs, writePermission)`. This makes them hot-applied and audited with no new table.
  - `SettingDef` gains `min` and `max`. For a duration, a `max` of `forever` also allows the value
    `forever`.
  - Lifecycle settings need `data:write`, and the Settings screen does not list them.
- **One purge job.** `housekeeping` is an `INSTALLATION` cron job ([ADR-0125](0125-installation-wide-jobs-run-once-through-shedlock.md)).
  - For each store not kept forever, it writes one `PURGE_STORE` audit event, then calls
    `purgeBatch(cutoff, 5000)` until it returns 0. The job is not transactional, so every batch
    commits alone.
  - A store that throws is recorded as a partial failure, and the next store runs.
  - The last purge of each store is kept in `lifecycle_purge`.
- **Row stores** delete by `ctid` in batches (`LifecycleSql`). **Partition stores** drop one expired
  partition per batch. The partition maintainers keep only partition creation.
- **Nothing grows unnoticed.** `StoreCoverageTest` fails when a changelog creates a table that no
  store names and that is not listed as bounded by design, with a reason.
- The old per-module reapers, their jobs and their settings keys are removed. `Contract.VERSION`
  goes to 5.

## Consequences

- Every retention is on one page, previewable, audited, and bounded to its store's range. Audit
  keeps everything by default and can now be bounded.
- A first purge of a large backlog takes many batches. It runs longer, but it never blocks writers
  for more than one batch.
- Existing overrides of the removed keys (`events.retention-hours`, `rr.retention-days`,
  `metric.retention-days` and their crons) are dropped. Operators set them again on the Data page.
- Plugins implement one interface to have their data purged, previewed and shown like Studio's own.
