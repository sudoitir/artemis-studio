# ADR-0193: Partitioned history with no default partition and one maintainer

- **Status**: accepted; amends [ADR-0134](0134-one-data-lifecycle-for-every-store.md)
- **Date**: 2026-10-13
- **Deciders**: Mahdi Amirabdollahi

## Context

`metric_sample` and `message_index` were partitioned by day, each with a default partition and its own
maintainer. The default partition made the maintainer move rows out of it on every run, under a lock
that stalled writers, and Postgres refuses `DETACH PARTITION ... CONCURRENTLY` on a table that has one,
so retention detached with a plain `DETACH` that blocks every insert behind any long query. The two
maintainers were two copies of the same code. `broker_event`, `rr_event` and `audit_event` grow with
traffic as well, and were purged row by row: bloat, vacuum load and a long tail of batches.

## Decision

1. **Five tables are range-partitioned on a time column**, each with the partition column in its
   primary key: `metric_sample.ts`, `message_index.observed_at`, `broker_event.received_at` and
   `rr_event.ts` by day, and `audit_event.ts` by month. Bounds are UTC. **No table has a default
   partition**, and the schema lint refuses one. `rr_event` has no foreign key to its flow.
2. **One `PartitionMaintainer` in `kernel.lifecycle.partition`** replaces both maintainers. Each module
   contributes a `PartitionSpec` bean: table, column, grain, how many periods ahead and the heap storage
   parameters, which a partitioned parent cannot carry, so every partition gets them.
3. **It runs at start-up**, in a lifecycle phase before scraping, capture and jobs, and **every hour** as
   the installation-wide `partitions` job. It creates the table, then attaches it, because `ATTACH`
   takes `SHARE UPDATE EXCLUSIVE` on the parent where `PARTITION OF` takes `ACCESS EXCLUSIVE`. A
   transaction-scoped advisory lock makes replicas take turns. A partition whose period closed is
   frozen and analyzed once (marked by a table comment); each parent is analyzed daily, because
   autovacuum never analyzes a partitioned parent.
4. **Retention drops partitions.** A store's `purgeBatch` calls `PartitionDrops.dropOldest`, which
   finishes any detach a crash left pending, then runs `DETACH ... CONCURRENTLY` and `DROP`. The
   housekeeper and the store SPI are unchanged.
5. **Writes never use broker time as the partition column.** The columns are the database's clock or
   the writer's. A write that still finds no partition fails with SQLState `23514`; the writer calls
   `ensureAhead()` and retries once, in a new transaction. The `partitions` health contributor is DOWN
   while a table lacks the current period's partition.
6. **Audit (ADR-0190).** A month is dropped only when no row in it is outside the eligible set:
   acknowledged by every reliable receiver or older than the maximum age, and covered by no hold. The
   gaps for rows a receiver had not acknowledged are recorded first. Otherwise the month is purged row
   by row, as before.
7. **Cascaded children are purged first in bounded batches** (`transfer_copied`, `bulk_run_item`), and
   the parents after them, so no statement cascades through an unbounded number of rows.
8. **A subscription's own shorter retention** on the message index is still a bulk delete, now in the
   hourly `message-index-retention` job, since a shared partition cannot be dropped for one subscription.

## Consequences

- Retention is a catalog operation: no bloat, no vacuum debt and no blocked writer.
- Writes outside the maintained window fail until the maintainer runs. That is deliberate and visible
  in health, and the retry covers a missed run; the cost is that a partition column must never come from
  a broker's clock.
- Primary keys are no longer unique on the key alone: the identity or UUID prevents duplicates, and the
  database cannot enforce more across partitions.
- Rows of `rr_event` outlive their flow by up to a day.
- The `metric.partition-maintainer-cron` setting is gone; the hourly job is not configurable.
- Operators recreate the database (ADR-0192).

## Alternatives considered

- **Keep a default partition.** Rejected: it blocks concurrent detach and invites rows that no date
  range can drop.
- **`PARTITION OF` for creation.** Rejected: it takes `ACCESS EXCLUSIVE` on the parent.
- **`pg_partman`.** Rejected: an extension that a managed external database may not allow.
- **Keep deleting `broker_event` and `rr_event` row by row.** Rejected: it is the load partitioning removes.
