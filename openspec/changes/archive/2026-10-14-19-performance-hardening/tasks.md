## 1. Design

- [x] 1.1 Brainstorm and investigate; `/opsx:update` adds design.md, sharpens specs, replaces these tasks

## 2. U1 Schema (database-schema)

- [x] 2.1 Re-baseline every module changelog to one `changes/0001-baseline.sql` with final shapes: uuid PKs `DEFAULT uuidv7()` (bearer-handle tables without default), PKs for `storage_sample`, `flow_route`, `metric_sample`; `inbox_item.data` jsonb; drop `pgcrypto`; partitioned parents for `broker_event`, `rr_event`, `audit_event` (no default partition, no partitions); storage parameters by class; plugin template SQL on `uuidv7()`
- [x] 2.2 UNLOGGED `heavy_permit` and limiter counter tables in their owning modules
- [x] 2.3 Delete `SchemaBaselineDiffTest` and `db/reference-schema.sql`; add `SchemaLintTest` with its allow-list
- [x] 2.4 Liquibase session advisory lock (`liquibase-sessionlock` or a small `LockService`)
- [x] 2.5 ADR: UUIDv7 keys and the second re-baseline, superseding the "never edit a released changeset" convention until the stable release; update CLAUDE.md non-negotiable 7 and the master changelog comment

## 3. U2 Ids

- [x] 3.1 `kernel/core/Ids.v7()` over Hibernate's `UuidVersion7Strategy`
- [x] 3.2 Every entity `@UuidGenerator(style = VERSION_7)`; JDBC inserts `RETURNING id`; replace `HeldStore`'s `SELECT uuidv7()`, `MessageIndexService`, `DiagnosticsService` (stays v4: bearer), `SecretRotations`
- [x] 3.3 `metric_sample` writer `ON CONFLICT DO NOTHING`

## 4. U3 Partitions (data-lifecycle, audit-log, scrape-scheduling, message-index)

- [x] 4.1 `kernel/lifecycle/partition`: `PartitionSpec`, `PartitionMaintainer` (boot SmartLifecycle under advisory lock, hourly `partitions` job, create + `ATTACH`, UTC, freeze closed partitions, analyze parents), `PartitionDrops.dropOldest` (`FINALIZE` pending, `DETACH CONCURRENTLY`, `DROP`), `partitions` health contributor
- [x] 4.2 Specs for `metric_sample`, `message_index`, `broker_event`, `rr_event`, `audit_event`; delete the two old maintainers and their jobs
- [x] 4.3 Stores drop partitions in `purgeBatch`; `AuditStore` drops a month only when every row is eligible, else row purge in range
- [x] 4.4 Writers retry once after `23514` with `ensureAhead()`
- [x] 4.5 Child-first bounded deletes for transfer, bulk and rr cascades
- [x] 4.6 ADR: partitioned history with no default partition (amends 0134)

## 5. U4 Jobs and threads (operational-health)

- [x] 5.1 `JobRunner`: timer thread, virtual thread per run, next fire after completion, per-job timeout, lock extension with `lockLost`, `invalidSchedule` re-parse, UTC, first-failure logging, counters; replaces `JobScheduler`, the scrape scheduler pool and the plugin job bridge pool
- [x] 5.2 `ScheduledJob` optional timeout; status fields in `JobStatus` and the jobs API
- [x] 5.3 Fix the SSE drain-thread leak; record interrupted approval executions
- [x] 5.4 `ThreadLeakIntegrationTest`
- [x] 5.5 ADR: the job runner (amends 0048, supersedes 0125's job list)

## 6. U5 Database configuration

- [x] 6.1 HikariCP settings and connection timeouts in `application.yml`; remove `spring.task.scheduling`
- [x] 6.2 `SET LOCAL statement_timeout` for SQL console, exports, lifecycle preview, partition maintenance
- [x] 6.3 One `deploy/postgres/postgresql.conf` mounted by prod, HA and dev compose; remove the `-c` lists and `postgresql.tuning.conf`; update CLAUDE.md's pointer
- [x] 6.4 ADR: connection budget and the shipped Postgres configuration

## 7. U6 Limits (performance-sizing)

- [x] 7.1 `kernel/core/HeavyRequests` (cross-replica permits, time and row bounds, response names the bound, installation cap pool/4)
- [x] 7.2 Apply to audit export and history, alert and event history, bulk and transfer previews, flow and topology reads, SQL console; keyset paging on history
- [x] 7.3 Login attempt, upload and in-flight limiters on Postgres counters; node call limiter divided by ready replicas
- [x] 7.4 `BrokerListOps.readAll` chunks of 200; `studio.broker.calls{kind}`
- [x] 7.5 ADR: heavy-request permits across replicas

## 8. U7 Stream (realtime-stream)

- [x] 8.1 Subscriber bounds (1,000 frames, 1 MiB), signal coalescing, bounded replay hold buffer with resync, drop rule with `reconnect`, `studio.sse.dropped`
- [x] 8.2 Verify `broker_event.seq` replay against late commits; switch to `(txid, seq)` if needed
- [x] 8.3 ADR: stream backpressure (amends 0018)

## 9. U8 Grids (operator-ui)

- [x] 9.1 Memoise the measurement row scan; in-grid find
- [x] 9.2 Move `.map` lists that can exceed 200 items onto `DataTable`
- [x] 9.3 50k-row browser test (time to interactive, bounded DOM rows)

## 9b. U11 External database (database-schema)

- [x] 9b.1 `DatabasePreflight` before Liquibase: version 18+, privileges, session stability (refuse transaction-mode pooling), warnings for connection budget and server timeouts
- [x] 9b.2 Non-default schema: no SQL names `public`; integration test migrating and booting in a non-default schema as a restricted, non-superuser role
- [x] 9b.3 Failover: multi-host URL support, `LISTEN` reconnect with resync, session advisory lock holders detect a dropped connection and stop their duty; partition maintainer on transaction-scoped locks
- [x] 9b.4 Optional read-only pool `studio.database.read.*` with `ReadRouting` (lag check, fall back to primary, response marks possible lag)
- [x] 9b.5 `docs/external-database.md`; ADR: Studio on an external PostgreSQL

## 10. U10 Load test and sizing (performance-sizing)

- [x] 10.1 Gatling project in `load/`, `just load`, `workflow_dispatch` workflow
- [x] 10.2 Run it on the reference profile; write `docs/sizing.md`
- [x] 10.3 README hardware section (minimum and recommended, single and HA)
- [x] 10.4 ADR: the Gatling load test
- [x] 10.5 Tier C walks every queue page of a node in each tick; the sweep cursor goes (ADR-0200)

## 11. Finish

- [x] 11.1 `openspec validate 19-performance-hardening --strict`
- [x] 11.2 Narrow tests per unit green; push; CI `ci-ok` and Sonar gate green; PR merged
- [x] 11.3 `/opsx:archive`; tick `ROADMAP.md`; worktrees and branches removed
