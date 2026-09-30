## 1. Groundwork (inline)
- [x] 1.1 ADR-0132 (data lifecycle engine and HousekeepingContributor SPI) and ADR-0133 (installation-scoped alert rules)
- [x] 1.2 `SettingDef` min/max and `forever`; `SettingsService.addSettings(namespace, defs, read, write)` replacing `addPluginSettings`; per-setting write permission; `effective()` lists only settings-owned keys; Contract.VERSION 4 → 5 (Java and web)
- [x] 1.3 `kernel/lifecycle` module: `@PluginApi` `HousekeepingContributor`, `ManagedStore`, `StoreDef`, `StoreUsage`, `PurgeEstimate`; `DataPermissions`; `LifecycleRegistry` (core and plugin stores, generated settings); `HousekeepingPluginBridge`; `LifecycleSql` batch helpers
- [x] 1.4 `HousekeepingJob` (INSTALLATION, batched, per-store failure isolation, `PURGE_STORE` audit) and `lifecycle_purge` status table
- [x] 1.5 Engine tests: batching, forever skip, failing store isolation, audit event, bounds rejection

## 2. Core stores (parallel implementers)
- [x] 2.1 `metrics` and `message-index` partition stores; maintainers keep create-ahead only; subscription retention capped by the store; old reaper, keys and jobs removed
- [x] 2.2 `broker-events`, `rr-flows`, `captured-payloads`, `expired-sessions` (Spring Session cleanup disabled); old reapers, keys and jobs removed
- [x] 2.3 `audit`, `bulk-runs`, `transfer-runs` (preview housekeeping jobs folded in)
- [x] 2.4 `alert-history`, `broker-config-history`, `setup-reviews`, `classification-findings`
- [x] 2.5 `StoreCoverageTest` with the bounded-by-design allowlist

## 3. Storage health and installation alerts
- [x] 3.1 `storage_sample` table and `storage-samples` store; `StorageHealthService` (pg_stat, sizes, growth, dead tuples, vacuum, partition coverage); daily `storage-sample` job
- [x] 3.2 installation rules (`cluster_id IS NULL`, `alert_firing.cluster_id` nullable); `InstallationSignalSource`; `STORAGE_QUOTA` and `STORAGE_HEALTH` signals; `AlertEvaluator.evaluateInstallation`; seeded installation rules; alerts UI shows "Installation"

## 4. API and UI
- [x] 4.1 `/api/v1/data` controller (stores, update, preview, health) with `data:read`/`data:write`; OpenAPI regenerated
- [x] 4.2 `web/src/features/data/`: Retention and Storage health tabs, policy editor with preview, empty and error states, vitest

## 5. Finish
- [ ] 5.1 `just verify` and `-Papi-check` green; screenshots light and dark
- [ ] 5.2 PR merged on green CI; change archived
