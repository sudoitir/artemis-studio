## Context

Retention is spread over eight reapers, one per module (`MetricSampleReaper`, `MetricPartitionMaintainer`,
`MessageIndexPartitionMaintainer`, `BrokerEventReaper`, `RrFlowReaper`, and the bulk and transfer preview
housekeeping). Each has its own settings keys and runs one unbounded `DELETE` or drops partitions.
`audit_event`, `alert_firing`/`alert_delivery`, finished `bulk_run`/`transfer_run`, `broker_config_revision`/
`broker_config_apply`, `setup_review`/`setup_finding` and `classification_finding` are never cleaned up.
Spring Session JDBC runs its own expired-session cleanup on every instance. Settings (ADR-0047) have no
per-setting bounds. Alert rules are per cluster only (`alert_rule.cluster_id NOT NULL`). Installation-wide
jobs run once through ShedLock (ADR-0125).

## Goals / Non-Goals

**Goals:**
- One engine that purges every store in bounded batches, once per installation, and audits each purge.
- Core stores and plugin stores go through the same SPI and the same page.
- A test that fails when a table that grows with use has no store.

**Non-Goals:**
- Changing what a store records. Archiving what is purged. Legal hold.
- Table-level tuning (autovacuum settings). The health page reports; it does not tune.

## Decisions

### 1. One `HousekeepingContributor` SPI for core and plugin stores (ADR-0132)
`kernel/lifecycle` holds three `@PluginApi` types:
- `HousekeepingContributor`: `List<ManagedStore> stores()`;
- `ManagedStore`: `StoreDef def()`, `StoreUsage usage()`, `PurgeEstimate preview(Instant cutoff)`,
  `long purgeBatch(Instant cutoff, int limit)`;
- `StoreDef(id, label, List<String> tables, QuotaUnit quotaUnit, Duration defaultRetention,
  Duration minRetention, Duration maxRetention)`, where a `null` `defaultRetention` keeps everything
  by default and a `null` `maxRetention` allows keeping everything (`forever`).

Each module implements its stores as contributor beans, so a disabled feature's stores disappear with it.
Plugins contribute through `HousekeepingPluginBridge` (a `PluginBridge`, like `SettingsPluginBridge`), and
their store ids are prefixed `<pluginId>.`.

*Alternatives:*
- Keep the reapers and put a read-only registry over them. That leaves eight places to batch and audit,
  and plugins cannot join the purge loop.
- A per-store `storeId` dispatch interface. A `ManagedStore` object per store is simpler to implement
  and to test.

### 2. Policies are settings with bounds and their own permission
Each store gets three settings, `lifecycle.<storeId>.retention|quota|quota-warn-percent`, registered at
runtime through `SettingsService.addSettings(namespace, defs, readPermission, writePermission)`. This
replaces and generalises `addPluginSettings`. The settings store then gives validation, audit
(`UPDATE_SETTING`), caching and hot apply, with no new table.
- **Bounds:** `SettingDef` gains nullable `min` and `max`. For `DURATION` settings, the value `forever` is
  accepted only when `max` is the literal `forever`. An out-of-range value is rejected, naming the range.
- **Permissions:** a setting's write permission is kept in the registry entry. Lifecycle settings use
  `data:read` and `data:write`, and every other setting keeps `settings:read` and `settings:write`.
  `effective()` (the Settings page) lists only the `settings:*` ones, so each policy has one home, the
  Data page.
- **Quota:** an INT with `min 0`, where 0 means no quota. It is in MiB for a `BYTES` store and in
  thousands of rows for a `ROWS` store. The warning percentage is 1–100 with a default of 80.

*Alternative:* a dedicated `lifecycle_policy` table. That would re-implement validation, audit and hot
apply.

### 3. One batched `housekeeping` job
`HousekeepingJob` is an `INSTALLATION` cron job (setting `lifecycle.housekeeping-cron`, default
`0 30 3 * * *`), run through ShedLock. For each store whose policy is not `forever`:
1. It writes a `PURGE_STORE` audit event (`targetType STORE`, with the store and retention as params)
   through `AuditService.begin`.
2. It calls `purgeBatch(cutoff, 5000)` until the call returns 0. Every batch is its own short
   transaction, so a concurrent writer waits for one batch at most.
3. It records the outcome with `succeed(event, total)` or, when the store throws, with `fail`, and moves
   on to the next store.

Last run, amount and error per store go into `lifecycle_purge(store_id PK, last_run_at, last_purged,
last_error)` for the page. The purge audit row is newer than any cutoff, so the audit store never purges
its own record.

- **Row stores** use `LifecycleSql.deleteBatch(table, tsColumn, extraPredicate)`:
  `DELETE FROM t WHERE ctid = ANY(ARRAY(SELECT ctid FROM t WHERE ts < :cutoff AND … LIMIT :limit))`.
- **Partition stores** (metrics, message index) detach and drop one expired daily partition per batch,
  then batch-delete from the default partition. The partition maintainers keep only the create-ahead
  step.

### 4. The stores
| Store id | Tables | Rule | Default · bounds |
| --- | --- | --- | --- |
| `metrics` | `metric_sample*` | partitions by `ts` | 7d · 1d–90d |
| `message-index` | `message_index*` | partitions by `observed_at`. A subscription's `retention_days` must be ≤ the store's retention, which is its cap; the default is the longest a subscription may keep, so none is cut short | 90d · 1d–90d |
| `captured-payloads` | `rr_event.detail` body preview | strips the captured body (`detail - 'bodyPreview'`) from events older than the cutoff | 3d · 1h–90d |
| `broker-events` | `broker_event` | rows by `received_at` | 72h · 1h–90d |
| `rr-flows` | `rr_flow`, `rr_event`, `rr_expectation`* | rows by `requested_at`; events cascade | 7d · 1d–90d |
| `audit` | `audit_event` | rows by `ts` | forever · 30d–forever |
| `bulk-runs` | `bulk_run`, `bulk_run_item` | terminal runs by finish time. Expired previews keep their 10-minute rule and are purged in the same pass | 90d · 1d–3650d |
| `transfer-runs` | `transfer_run`, `transfer_copied` | the same, for transfers | 90d · 1d–3650d |
| `alert-history` | `alert_firing`, `alert_delivery` | rows by time | 90d · 1d–3650d |
| `broker-config-history` | `broker_config_revision`, `broker_config_apply` | rows by time, never a declaration's current revision | 365d · 7d–forever |
| `setup-reviews` | `setup_review`, `setup_finding` | reviews by time, never a cluster's latest | 90d · 1d–3650d |
| `classification-findings` | `classification_finding` | rows by time | 90d · 1d–3650d |
| `expired-sessions` | `spring_session*` | sessions whose expiry passed more than the retention ago; Spring Session's own cleanup is disabled | 0 grace, fixed · 1m–1d |
| `storage-samples` | `storage_sample` | rows by `sampled_at` | 90d · 7d–3650d |

\* `rr_expectation` is configuration; it is listed only in the allowlist.

The time columns come from each table's baseline. A store whose tables lack a usable timestamp gets one
in a Liquibase change.

`StoreCoverageTest` (in `architecture/`) parses every `CREATE TABLE` in the changelogs, as
`SchemaOwnershipTest` does. Every table must be named by a core `StoreDef.tables()` or appear in a
`BOUNDED_BY_DESIGN` map with a reason (configuration, identity, or self-trimmed like `flow_*` and
`queue_snapshot`).

### 5. Storage health
`StorageHealthService` reads the following for Studio's schema and each plugin schema:
- `pg_stat_user_tables`: `n_live_tup`, `n_dead_tup`, and last vacuum or autovacuum;
- `pg_total_relation_size`;
- `pg_inherits`, for partition coverage: today plus the next 3 days must have partitions.

The daily `storage-sample` INSTALLATION job writes per-table sizes to `storage_sample`, which gives
7-day growth, and then evaluates alerts. A table is unhealthy when any of these holds:
- dead tuples are over 20% of the table and over 10,000 rows;
- it has had no vacuum for 7 days despite dead tuples;
- a partition is missing;
- its store is over the quota warning.

### 6. Installation-scoped alert rules (ADR-0133)
- **Schema:** a rule with no cluster (`alert_rule.cluster_id IS NULL`) is about the installation;
  `alert_firing.cluster_id` becomes nullable to match.
- **Evaluation:** `AlertEvaluator.evaluateInstallation(kind)` evaluates such rules against
  `InstallationSignalSource` beans. There are two: `STORAGE_QUOTA` (a store over its warning) and
  `STORAGE_HEALTH` (a table unhealthy). Each subject names the store or table and its usage.
- **Seeding:** the changeset seeds one of each, once per database, as an ordinary rule that is
  editable, can be disabled and has no channel; a deleted one is not recreated.
- **UI:** installation rules and firings appear in every cluster's alerts view with an
  "Installation" badge; only a global `alert:read` / `alert:write` grant sees or edits them.

*Alternative:* seeding a per-cluster rule. It fires N duplicates for one database and never fires with
zero clusters.

### 7. API and UI
- **Permissions:** `data:read` and `data:write`, declared by the lifecycle module.
- **REST:**
  - `GET /api/v1/data/stores`: definition, effective policy, usage and last purge per store.
  - `PUT /api/v1/data/stores/{id}` with `{retention, quota, quotaWarnPercent}`: writes through the
    settings store, audited, 400 with the range when out of bounds.
  - `POST /api/v1/data/stores/{id}/preview` with `{retention}`: the estimate, with no delete.
  - `GET /api/v1/data/health`.
- **Web:** `web/src/features/data/` is an installation-level route, "Data", gated by `data:read`, with
  two tabs:
  - **Retention:** a table of stores, a policy editor with Preview, and Forever where the store allows it.
  - **Storage health:** a table of tables with the unhealthy ones flagged, plus partition coverage.

  Both tabs have empty and error states.

## Risks / Trade-offs

- **An unbounded `DELETE` replaced by many batches is slower overall.** Mitigation: batches are 5,000
  rows, and a first run on a large backlog simply spans more of the nightly window. Progress is audited
  per store.
- **`ctid` batching on a table under heavy update could skip rows.** Mitigation: it loops until a batch
  deletes 0, and the next run catches the rest.
- **Removing the old settings keys drops operators' existing overrides.** Accepted: no backward
  compatibility before 1.0, and the commit is marked BREAKING.
- **`pg_stat` numbers are estimates.** They are labelled as such on the page, and alerts use generous
  thresholds.

## Migration Plan

Liquibase adds `lifecycle_purge` and `storage_sample`, makes `alert_firing.cluster_id` nullable and seeds
the two installation rules. Stored overrides of the removed keys stay in `studio_setting` and are ignored,
as any unknown key is. Spring Session's cleanup cron is disabled in
`application.yml`. Contract.VERSION goes 4 → 5 (new SPI and a changed `SettingDef`).
