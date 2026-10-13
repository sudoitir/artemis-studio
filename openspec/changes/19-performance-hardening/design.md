# Design: 19-performance-hardening

## Context

See proposal.md for why. The change has two halves: the database and runtime hardening that the
measurements need underneath them, and the measured performance work on top.

What exists today:

- PostgreSQL 18 everywhere. 27 module changelogs, 96 SQL changesets (ADR-0008, ADR-0072).
- About 37 uuid primary keys default to `gen_random_uuid()` (v4), and 24 JPA entities use
  `GenerationType.UUID`, which is also v4. Random keys scatter B-tree inserts across the whole index.
  Only `held_operation` uses `uuidv7()`.
- `metric_sample` and `message_index` are partitioned daily, each with its own maintainer and a
  default partition. Every other history table is purged by batched `DELETE` (ADR-0134).
- Jobs are `ScheduledJob`s registered through a `SchedulingConfigurer` (`kernel/jobs/internal/JobScheduler`).
  Installation-wide jobs take a ShedLock lock (ADR-0125). Per-cluster duties use advisory locks and
  leases (ADR-0152).
- `spring.threads.virtual.enabled` is on. Neither HikariCP nor the connection's timeouts are configured.
- `GridTable` is already virtualized (`useVirtualizer`), and `JolokiaBrokerClient.batch` exists.
- The SQL console is the one endpoint with cost planning and bounded execution.

## Goals / Non-Goals

**Goals:**

- Keys and storage patterns that keep B-trees, vacuum and retention cheap as volume grows.
- Background work that cannot overlap itself, hang forever, die silently or leak a thread.
- Bounded resources everywhere a client or the data can grow them: connections, stream buffers,
  heavy requests.
- Published, measured, repeatable figures, with a minimum hardware statement in the README.

**Non-Goals:**

- Backward compatibility. Old databases are recreated and nothing is migrated (standing decision until
  `36-stable-release`).
- `pg_partman`, Redis, db-scheduler or any other new store or engine.
- Changing the polling model or the metrics store, and horizontal scaling beyond change 11.

## Decisions

### 1. Keys are UUIDv7, generated where the row is written

- Every uuid primary key defaults to `uuidv7()` (PG18 core). JPA entities use
  `@UuidGenerator(style = VERSION_7)` (Hibernate 7.4, incubating since 7.0). JDBC inserts that do not
  need the id beforehand use `RETURNING id`. Code that needs the id before the insert calls
  `kernel/core/Ids.v7()`, which delegates to Hibernate's `UuidVersion7Strategy`, so there is one
  generator and no new dependency.
- Time-ordered keys keep inserts at the right edge of the index, which means fewer page splits, a
  denser index and a warmer cache.
- **Bearer handles stay v4.** SQL query tickets and diagnostics snapshot ids are credentials, and a v7
  id is partly predictable. Their columns have no default, and the schema lint allow-lists them by name.
- `bigint GENERATED ALWAYS AS IDENTITY` stays only where the number is a cursor or an order: the audit
  id, `broker_event.seq` (the stream's replay cursor), the rr, alert and held-operation `seq`, and inbox.
- Every table gets a primary key. `metric_sample`'s lookup index becomes its key, and the writer uses
  `ON CONFLICT DO NOTHING`. PG18's B-tree skip scan serves the queries that leave out `node_id`.
- `inbox_item.data` becomes `jsonb`, and the unused `pgcrypto` extension is dropped.
- *Alternatives:* a v7 library such as uuid-creator, which adds a dependency for what Hibernate
  already ships; database-only generation, which costs a round trip wherever the id is needed first.

### 2. One baseline per module, guarded by a schema lint

- Each module's changelog is re-baselined to one `changes/0001-baseline.sql` with the final shapes, as
  ADR-0072 did once already. The baseline is built from a `pg_dump --schema-only` of today's schema,
  split per module and then edited. `SchemaBaselineDiffTest` and `db/reference-schema.sql` go, because
  the history they compare against no longer exists.
- `SchemaLintTest` reads the catalog of the migrated database and fails on:
  - `gen_random_uuid`, `serial`, `json`, `timestamp` without time zone, `varchar(n)`, or a default partition;
  - a table without a primary key;
  - a foreign key whose columns do not lead some index;
  - a uuid primary key whose default is not `uuidv7()`;
  - a bigint primary key that is not an identity.
  
  Exceptions live in one allow-list, each with its reason: ShedLock's table, the Spring Session tables
  and the bearer-handle tables.
- **Liquibase takes a session advisory lock** instead of its lock row, so a replica that dies
  mid-migration releases the lock rather than wedging every later boot. Use `liquibase-sessionlock` if
  it supports the Boot-managed Liquibase; otherwise write a small `LockService`.
- Migrations inherit the connection's `lock_timeout` (§5). In HA a blocked DDL fails and the boot is
  retried, instead of queuing all traffic behind `ACCESS EXCLUSIVE`. Indexes added after the baseline use
  `CREATE INDEX CONCURRENTLY` with `runInTransaction:false`. On a partitioned table that means `ON ONLY`
  the parent, then a concurrent index per partition, then `ATTACH`.

### 3. High-volume history is partitioned, with no default partition

| Table | Partitioned by | Grain |
|---|---|---|
| `metric_sample` | `ts` | day |
| `message_index` | `observed_at` | day |
| `broker_event` | `received_at` | day |
| `rr_event` | `ts` | day |
| `audit_event` | `ts` | month |

- These grow with traffic. Everything else grows with operators or alerts, so it keeps batched `DELETE`
  with tight autovacuum.
- The partitioned primary key is (key, partition column). `rr_event`'s foreign key to its flow is
  dropped: readers join through `rr_flow`, and an orphan lives at most one partition.
- **One `kernel/lifecycle/partition/PartitionMaintainer`** replaces `MetricPartitionMaintainer`,
  `MessageIndexPartitionMaintainer` and their two jobs. Modules contribute `PartitionSpec` beans with
  table, column, grain, how far ahead and storage parameters.
- `ensureAhead()` runs in two places: at boot in a `SmartLifecycle` phase before scrape, capture and
  jobs, under an advisory lock; and every hour from the `partitions` installation-wide job.
- It creates each partition and then attaches it with `ATTACH`, which takes `SHARE UPDATE EXCLUSIVE`
  on the parent, rather than `PARTITION OF`, which takes `ACCESS EXCLUSIVE`. Bounds are in UTC.
- A closed partition gets `VACUUM (FREEZE, ANALYZE)` once. Parents get a daily `ANALYZE`, because
  autovacuum never analyzes a partitioned parent.
- **Retention drops whole partitions.** `PartitionDrops.dropOldest` is called from each store's
  `purgeBatch`, so `Housekeeper` and the `StoreDef` SPI stay as they are. It finishes any pending detach
  with `FINALIZE`, then runs `DETACH PARTITION … CONCURRENTLY` and `DROP`.
- `DETACH … CONCURRENTLY` is refused while a default partition exists, and a plain `DETACH` blocks
  every insert behind any long query. That is why there is no default partition.
- **Without a default partition, a write outside the window fails.** Partition columns are set by the
  database or by the writer's clock, never by broker time. A writer that gets SQLState `23514` calls
  `ensureAhead()` and retries once. The `partitions` health contributor reports DOWN when the current
  period has no partition.
- **Audit (ADR-0190).** A month is dropped only when every row in it is eligible: acknowledged by every
  reliable receiver or older than the maximum age, and covered by no hold. Otherwise that month is
  purged row by row as today, so gap recording is unchanged.
- **Cascaded children are purged child-first in bounded batches** (`transfer_copied`, `bulk_run_item`),
  restoring the bounded-batch rule that today's `ON DELETE CASCADE` breaks.

### 4. Storage parameters by class, and one shipped Postgres configuration

| Class | Storage parameters |
|---|---|
| Append-only history | `fillfactor=100`, insert-triggered vacuum (`autovacuum_vacuum_insert_threshold`, `scale_factor=0`), and a low analyze scale factor, so the visibility map stays current for index-only scans |
| Hot-update state (`queue_snapshot`, leases, replica rows, flow and setup state, runs) | `fillfactor` 50–80 for HOT updates, a low vacuum scale factor |
| UNLOGGED expiry tables | vacuum scale factor 0 and a small threshold |

Partitions do not inherit heap parameters, so the partition spec applies them to each partition.

`deploy/postgres/postgresql.conf` is one complete file, mounted by the prod, HA and dev compose files.
It replaces their drifting `-c` lists. It holds:
- `pg_stat_statements` with `compute_query_id`;
- autovacuum workers, cost limit, naptime and logging;
- `log_lock_waits` and `track_io_timing`;
- `timezone=UTC`;
- `idle_in_transaction_session_timeout` as a backstop;
- `max_connections`;
- `io_method=worker`, set explicitly, because Docker's default seccomp profile blocks `io_uring`.

PG18's `initdb` enables data checksums by default.

### 5. Connections are bounded and fail fast

- **HikariCP:** pool and minimum idle 16, `connection-timeout=5s`, `max-lifetime=30m`, `keepalive-time=2m`,
  `leak-detection-threshold=120s`. With virtual threads, a long pool wait only piles up threads, so
  failing fast is the better behaviour.
- **On every connection:** `statement_timeout=30s`, `lock_timeout=5s`, `idle_in_transaction_session_timeout=60s`,
  plus pgJDBC `connectTimeout`, `socketTimeout` and `tcpKeepAlive`. Today a network partition can hang a
  thread forever.
- **Long paths raise the limit for one transaction** with `SET LOCAL statement_timeout`: the SQL console,
  exports, the lifecycle preview and partition maintenance.
- **Heavy work holds at most a quarter of the pool** across the installation (§8).

### 6. Jobs run on their own virtual thread, never overlap, and are bounded in time

- **Verified defect.** With virtual threads enabled, Boot 4.1 configures `SimpleAsyncTaskScheduler`. It
  ignores `pool.size` and hands each trigger run to a new thread, so a fixed delay is measured from the
  dispatch and a slow per-replica job overlaps itself.
- **`kernel/jobs/internal/JobRunner`** replaces `JobScheduler`, the scrape scheduler's pool and the
  plugin job bridge's pool.
  - One platform timer thread. Each run gets a virtual thread named `job-<id>`, with the host
    classloader as context loader.
  - The next fire is computed only after the run ends.
- **`ScheduledJob` gains an optional timeout,** defaulting to the larger of 5 minutes and three
  intervals, or 1 hour for cron. At the timeout the run is interrupted and recorded as timed out, and
  no new run starts until its thread has ended.
- **The runner extends the ShedLock lock itself.** A failed extend interrupts the run and records
  `lockLost`. There are no fencing tokens (ADR-0152 stands): interruption plus per-row idempotency
  (conditional updates, `SKIP LOCKED`, `IF NOT EXISTS`) make a rare double run harmless.
- **A malformed cron** marks the job `invalidSchedule`, which shows as degraded. The runner re-parses
  it every minute. Today the job stops forever with no sign.
- **All schedules run in UTC.** Only the first failure and the recovery are logged.
- **New counters:** `studio.job.timeouts`, `studio.job.lock_lost`, `studio.job.overruns`.
- **Shutdown:** pause, wait, then interrupt whatever is left.
- The `spring.task.scheduling` block goes from `application.yml`. The new ADR replaces ADR-0125's
  stale job list.
- *Alternative:* db-scheduler. It covers installation-wide jobs only, so it would mean two job systems
  next to ShedLock and a runner. Rejected.

### 7. No thread outlives the context

- The runner removes the scrape and plugin pools, which are not daemon threads.
- The SSE `Subscriber` drain thread is released on every path, not only on completion.
- An interrupted approval execution is recorded as interrupted.
- **`ThreadLeakIntegrationTest`** starts the full context, opens a stream, runs a job and a plugin, and
  closes the context. It then dumps every thread, virtual ones included
  (`HotSpotDiagnosticMXBean.dumpThreads`), and fails on any Studio-named thread or any non-daemon thread
  the JVM did not start.

### 8. Heavy endpoints hold permits counted across replicas

- **`kernel/core/HeavyRequests`** gives each endpoint a per-user concurrency cap, a time bound and a row
  cap. The response names the bound it reached.
  - Permits are rows in an UNLOGGED `heavy_permit` table, claimed with `INSERT … ON CONFLICT DO NOTHING`,
    released in `finally` and expired by lease. Per-replica maps would double every limit in HA.
  - The time bound is a `SET LOCAL statement_timeout` plus a deadline; the size bound is a row cap.
- **It covers:** audit export and history, alert and event history, bulk and transfer previews, flow
  and topology reads, and the SQL console, which drops its per-replica map. History endpoints page by
  keyset.
- **The other limiters move to Postgres counters** (`ON CONFLICT DO UPDATE`): the login attempt limiter,
  the upload rate limit and the request limiter's in-flight map.
- **The node call limiter instead divides its rate by the number of ready replicas,** because a
  database round trip per Jolokia call would cost more than it saves.
- **Kept as they are:** caches invalidated through StudioBus, `api_request_window`, ShedLock, advisory
  locks, leases and the `SKIP LOCKED` queues (the Postgres-first review).

### 9. Slow stream subscribers are coalesced, then dropped

- **Bounds:** each subscriber's queue holds at most 1,000 frames and 1 MiB. A queued change signal is
  replaced by a newer signal for the same (topic, about).
- **The replay hold buffer gets the same bound.** On overflow it turns into a resync.
- **Drop rule:** three overflows within 60 s, or one write blocked for more than 30 s, sends `reconnect`
  and completes the stream. The client replays from its last event id. `studio.sse.dropped` counts drops.
- **The implementer verifies** that `broker_event.seq` replay cannot skip a row that commits late. If it
  can, the cursor becomes `(txid, seq)`.

### 10. Grids, broker batching, load test and sizing

- **Grids:** memoise the measurement's full row scan, add in-grid find (browser find cannot see virtual
  rows), and move any `.map` list that can exceed 200 items onto `DataTable`. A 50k-row browser test
  asserts time to interactive and a bounded DOM row count.
- **Broker:** `BrokerListOps.readAll` chunks at 200 instead of one unbounded POST.
  `studio.broker.calls{kind}` counts calls.
- **Load test:** Gatling (Java DSL), in a standalone `load/` Maven project outside the main build. Gatling
  has native SSE support; k6 would need a custom build.
  - `just load` starts Studio in HA under its own compose project, with N brokers, and seeds clusters
    and queues.
  - Its profiles cover UI reads, fast and stalled SSE subscribers, exports and SQL.
  - It finishes by scraping `/actuator/prometheus` (heap, pending connections, SSE queue depth, job lag,
    broker calls).
  - It also runs from a `workflow_dispatch` workflow, not on every PR.
- **`docs/sizing.md`** uses a 4 vCPU / 8 GB reference machine with Postgres on the same host. Each
  dimension states the tested ceiling, what degrades first, the profile, the version and the date. A run
  below a published figure prints `REGRESSION`.
- **The README gets a hardware section:** minimum and recommended, for single and HA, all taken from
  the measurements.

## Risks / Trade-offs

- [A write lands outside the partition window] → partition columns use database or writer time; a
  `23514` triggers `ensureAhead()` and one retry; health reports DOWN.
- [Hibernate's `VERSION_7` style is incubating] → it sits behind `Ids` and the annotation, and if it
  changes there is one place to swap.
- [Pool wait of 5 s fails requests under a burst] → heavy work holds at most a quarter of the pool, and
  the load test sizes the pool against the reference machine.
- [Interrupting a timed-out job leaves partial work] → jobs are idempotent per row; the guide and the
  job status show the timeout.
- [The re-baseline drops changeset history] → greenfield, and `SchemaLintTest` plus the ownership tests
  guard the result.
- [A partition is dropped while a plugin still needs its audit rows] → a month drops only when every row
  is eligible under ADR-0190.

## Migration Plan

None. Databases created by earlier versions are recreated (`docker compose -p … down -v`). The release
note says so under `BREAKING CHANGE:`.

## New ADRs

UUIDv7 keys and the second re-baseline (amends 0072 and 0008); partitioned history with no default
partition (amends 0134); the job runner (amends 0048, supersedes 0125's job list); the connection budget
and the shipped Postgres configuration; heavy-request permits across replicas; stream backpressure
(amends 0018); the Gatling load test.
