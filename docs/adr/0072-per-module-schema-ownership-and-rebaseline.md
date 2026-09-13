# ADR-0072: Each module owns its tables; the schema history is re-baselined once, per module

- **Status**: accepted
- **Date**: 2026-09-13
- **Deciders**: Mahdi Amirabdollahi
- **Supersedes**: the history-continuity part of [ADR-0008](0008-schema-migrations-liquibase.md) (Liquibase, XML master and SQL changesets remain)
- **Depends on**: [ADR-0069](0069-kernel-plugin-modular-monolith.md)

## Context

Twenty-five changesets live in one `changes/` directory, grouped by date rather than owner. Several concerns span non-adjacent files: identity in 003/014, alerting in 006/013/020, request-reply in 007/011/017/020. File 020 alters three concerns at once.

Every entity and repository sits in one `persist/` package, and `audit_event` has foreign keys to `app_user`, `cluster` and `broker_node`. Removing a cluster sets its audit rows' `cluster_id` to null, erasing the attribution the audit trail exists to keep.

Liquibase identifies a changeset by id, author **and file path**. Moving a released file to a module directory therefore makes Liquibase see it as new. Existing installations would re-run it, or need `logicalFilePath` shims on every moved file.

Studio is pre-stable. Every release is a `:dev` pre-release, and no stable tag exists.

## Decision

- **Ownership.** Every table belongs to exactly one module. Only that module's `internal.persistence` package holds its entities, repositories and JDBC access. Other modules read through the owner's `api`.
- **Layout.**
  - `db.changelog-master.xml` includes `db/changelog/<module>/changelog.xml` in topological order: kernel core, security, audit, settings; then clusters, scrape, apitokens, events, alerting, rr, sql, brokerconfig.
  - Each module changelog uses `includeAll path="changes/" relativeToChangelogFile="true"`.
- **One-time re-baseline.**
  - `changes/001–025` are replaced by one `0001-baseline.sql` per module, generated from a database migrated by the old history and split by owner.
  - Column order (non-negotiable #7), storage parameters, partitions, full-text configuration and seeds are preserved.
  - A schema-diff test fails on any difference except the deliberate ones below.
- **Deliberate differences.**
  - `oidc_role_mapping` becomes `identity_group_mapping` keyed by provider (ADR-0073).
  - `app_user` gains `provider_id` and `external_subject`.
  - `audit_event` drops its three foreign keys and gains `cluster_name`, so an audit event outlives the user, cluster or node it names.
- **Cross-module foreign keys** must follow an allowed module dependency. `SchemaOwnershipTest` enforces this.
- **Disabled features still migrate**, so enabling one never needs a migration.
- **Released changesets stay immutable.** This re-baseline is the single recorded exception, taken while no stable release exists.

## Consequences

- **BREAKING:** an existing database cannot be upgraded. Operators recreate the database and re-register clusters, users, roles, tokens, channels, rules, capture subscriptions and configuration declarations. The release notes carry the steps.
- Rolling back means the previous image plus a database restored from a pre-upgrade backup.
- A module's schema is readable in one directory, and adding a module adds a directory.
- Audit history survives cluster and user removal.
- Changesets written after the baseline are again never edited once released.

## Alternatives considered

- **Freeze 001–025 in place and add new changesets per module.** Existing installs keep upgrading, but ownership would be split forever between a historical blob and module directories. Rejected by the maintainer in favour of a clean layout while the project is pre-stable.
- **Move the files with `logicalFilePath` preserved.** Ownership would be readable, but every moved file would carry a path it no longer lives at, and 020 still spans three owners.
- **Schema per module (Postgres schemas).** Cross-module reads and the existing JPA mappings would need qualification everywhere for no operational gain.
