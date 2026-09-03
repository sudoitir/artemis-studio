# ADR-0008: Schema migrations with Liquibase (XML master, SQL changesets)

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi

## Context

The project needs a migration tool. The initial scaffold used Flyway; the owner
directed a switch to Liquibase, with an XML master changelog, changesets written
in SQL, and the `liquibase-maven-plugin` available for CLI operations. The schema
also needs deliberate physical tuning for a write-heavy workload (scrape upserts,
append-only metrics and audit).

## Decision

- **Liquibase.** Runtime migration runs on Spring Boot startup
  (`spring-boot-starter-liquibase`, Boot-managed version 5.0.3).
- **Master changelog is XML**:
  `src/main/resources/db/changelog/db.changelog-master.xml`, with explicit
  `<include>` of one **SQL changeset file per concern** under `changes/`
  (`001-extensions`, `002-estate`, `003-identity`, `004-audit`,
  `005-broker-cache`, `006-alerting`, `007-request-reply`).
- **`liquibase-maven-plugin`** (Boot-managed) is wired for humans:
  `updateSQL`, `status`, `rollback`, `diff` — surfaced as `just db-*`. It reads
  `liquibase.properties` (connection from env).
- **Physical tuning is part of the schema**, in the same changesets:
  - Column order minimises Postgres row padding: 8-byte-aligned types
    (`timestamptz`, `bigint`, `double precision`) first, then 4-byte (`integer`,
    and varlena `text`/`jsonb`/`bytea`/`inet`), then `uuid` and `boolean` last.
    The primary key is deliberately not first.
  - High-churn tables carry per-table `autovacuum` / `fillfactor` storage
    parameters: `queue_snapshot` and `alert_state` (`fillfactor` 80, HOT-friendly,
    aggressive vacuum); `audit_event`, `metric_sample`, `rr_event` (insert-tuned,
    `fillfactor` 100); `rr_flow` (`fillfactor` 90).
  - `metric_sample` is `PARTITION BY RANGE (ts)` with a default partition; BRIN on
    `ts`.
  - Server-level tuning (shared_buffers, WAL, autovacuum workers, planner SSD
    costs) lives in `deploy/postgres/postgresql.tuning.conf` and inline in the
    compose files.
- **Never edit a released changeset.** Add a new one. `checksum` drift fails
  startup.

## Consequences

- XML master gives explicit, reviewable ordering; SQL changesets keep the DDL
  readable and Postgres-native (partitioning, storage params, BRIN — things
  Liquibase's abstract change types express poorly).
- Rollback works per changeset (each has a `--rollback`).
- The column-order rule costs readability (PK mid-table); accepted for the
  padding saving on the wide, high-row tables.
- Autovacuum parameters are tuned for the documented target scale; revisit if
  real deployments diverge.

## Alternatives considered

- **Flyway** (initial scaffold) — simpler model, but the owner chose Liquibase
  for its changelog structure, rollback support, and `diff` tooling.
- **Liquibase with YAML/XML change types instead of SQL** — loses direct access
  to Postgres-specific DDL we rely on; rejected.
