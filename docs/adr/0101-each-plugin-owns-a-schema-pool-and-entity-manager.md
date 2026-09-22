# ADR-0101: Each plugin owns a schema, a connection pool and an EntityManager factory, and may not reference core tables

- **Status**: accepted
- **Date**: 2026-09-22
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/plugins`
- **Amends**: [ADR-0072](0072-per-module-schema-ownership-and-rebaseline.md) — plugin modules own a Postgres *schema*, not tables in `public`
- **Builds on**: [ADR-0008](0008-schema-migrations-liquibase.md), [ADR-0078](0078-audit-row-commits-before-the-broker-call.md)

## Context

A plugin needs its own tables and migrations, and they must not be able to break core:
- Liquibase with `defaultSchemaName` runs `SET SEARCH_PATH` on its connection, so a shared pool would leak a plugin's schema into core queries.
- A `SpringLiquibase` bean switches Boot's own Liquibase off.
- A foreign key from a plugin table to `cluster` would block cluster deletion and future core migrations.

## Decision

- **Each plugin gets schema `plugin_<id>`** and a **Hikari pool of 3 connections** whose init SQL sets `search_path`, `lock_timeout = 10s` and `statement_timeout = 60s`. It also gets its own EntityManager factory (`ddl-auto = validate`) and transaction manager.
- **Migrations run through Liquibase `CommandScope`, never a `SpringLiquibase` bean.** The runner holds a per-plugin advisory lock on one dedicated connection, releases stale Liquibase locks, then tags, then updates.
- **An activation fails, and the previous version resumes,** if the migration created a relation in `public` (partitions excluded) or a foreign key into `public`.
- **Audit writes go through the core pool** with `REQUIRES_NEW` (ADR-0078).
- **The connection budget** (10 + 3 × active plugins) is shown, and activation is refused above 80 % of `max_connections`.
- **Uninstall keeps the schema.** Purge drops it.

## Consequences

- A plugin cannot corrupt core queries, starve the core pool or block core deletes and migrations.
- Plugins cannot join core data in SQL. They store identifiers and react to republished core events.
- Every plugin costs up to 3 database connections.

## Alternatives considered

- **A shared pool with `hibernate.default_schema`.** Liquibase's `SET SEARCH_PATH` and session-scoped advisory locks leak across pooled connections.
- **Plugin tables in `public` with a name prefix.** Unenforceable, and they collide with core migrations.
- **A separate database per plugin.** Operationally heavy, and it breaks the one-database posture.
