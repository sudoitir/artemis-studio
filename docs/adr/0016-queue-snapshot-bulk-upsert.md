# ADR-0016: `queue_snapshot` is written by JDBC batch upsert, not JPA

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi

## Context

ADR-0011 chose JPA (Hibernate) for persistence and mandated dirty-checking
updates — "load the managed entity, set the changed fields in a `@Transactional`
method, let the flush issue `UPDATE ... WHERE id = ?`" — with anything beyond
that using `@Query` JPQL, never raw SQL.

That reasoning is entirely about the **estate** tables: `broker_node` rows are
referenced by `audit_event.node_id`, so their identity must be stable across
every refresh, and Spring Data JDBC's aggregate `save()` would churn child UUIDs.
`cluster`, `broker_node`, `broker_credential`, `broker_tls`, `audit_event` are
low-volume rows with meaningful identity and foreign keys.

`queue_snapshot` (changeset 005) is a different animal. CLAUDE.md calls it a
"disposable cache"; ADR-0006 says it is "rebuilt from the brokers on every
scrape … NOT the source of truth". Its PK is `(node_id, queue_name)` — a natural
key, no surrogate id, no inbound foreign keys. A Phase 2 tier-C sweep of a
3,000-queue node rewrites 3,000 rows per pass. Doing that through the Hibernate
persistence context — load-or-insert per row, dirty-check, flush — is the exact
workload JPA is worst at, and identity stability (the whole point of ADR-0011)
is irrelevant here because losing the entire table only costs a re-scrape.

## Decision

We will write `queue_snapshot` with a set-based JDBC upsert, not JPA:

- `persist/QueueSnapshotUpsert` uses `NamedParameterJdbcTemplate.batchUpdate`
  with `INSERT INTO queue_snapshot (...) VALUES (...) ON CONFLICT
  (node_id, queue_name) DO UPDATE SET ...` — one round trip per scrape batch.
- Stale rows (a queue removed on the broker) are reaped per node after a full
  sweep: `DELETE FROM queue_snapshot WHERE node_id = :n AND ts < :sweepStart`.
- Reads use a plain `@Entity QueueSnapshotEntity` + a Spring Data
  `JpaRepository` (`findByClusterId`). **Writes never go through JPA.**
- `spring-jdbc` (`NamedParameterJdbcTemplate`) is already on the classpath via
  `spring-boot-starter-data-jpa`. No new dependency.
- **ADR-0011 stands for every estate table.** This is a scoped exception for one
  disposable cache table, recorded because it departs from a stated convention.
- Liquibase remains the single schema source of truth; `ddl-auto=validate` still
  checks the read entity's mapping. `ON CONFLICT` targets the existing
  `pk_queue_snapshot` constraint; no schema change.

## Consequences

- The hot path is one `batchUpdate` per node per sweep instead of thousands of
  persistence-context operations. `fillfactor = 80` and the aggressive
  autovacuum already set on the table (changeset 005) are tuned for exactly this
  HOT-update pattern.
- A second write mechanism (raw SQL string) now exists alongside JPA. It is one
  file, one statement, covered by a Testcontainers test (`QueueSnapshotUpsertTest`:
  insert then update the same PK, reap by `ts`).
- `ON CONFLICT` is PostgreSQL-specific. Accepted: ADR-0006 already commits to
  PostgreSQL and forbids requiring extensions; portable SQL is not a goal for a
  disposable cache table.
- If `metric_sample` history writes (Phase 6) or another disposable
  broker-derived table appears, this ADR is the precedent: JDBC batch for
  disposable caches, JPA for the estate.

## Alternatives considered

- **JPA `saveAll` with `@SQLInsert` / `hibernate.jdbc.batch_size`.** Still
  routes every row through the persistence context and the composite natural key
  fights Hibernate's identity model. Marginal code saving, large runtime cost.
  Rejected.
- **`@Query` JPQL bulk `UPDATE` + separate `INSERT`.** JPQL has no upsert; we
  would hand-roll insert-then-update-on-fail, which is slower and racier than
  `ON CONFLICT`. Rejected.
- **jOOQ or MyBatis.** A new dependency and a codegen or mapping step for a
  single statement. Rejected (ADR-0011 rejected them for the same reason).
- **PostgreSQL `MERGE`.** Works, but `INSERT ... ON CONFLICT` is the idiomatic
  Postgres upsert, better documented, and maps cleanly to "latest state per
  queue". Chosen over `MERGE`.
