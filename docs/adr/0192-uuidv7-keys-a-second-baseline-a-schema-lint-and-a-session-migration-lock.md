# ADR-0192: UUIDv7 keys, a second re-baseline, a schema lint and a session migration lock

- **Status**: accepted; amends [ADR-0072](0072-per-module-schema-ownership-and-rebaseline.md) and [ADR-0008](0008-schema-migrations-liquibase.md)
- **Date**: 2026-10-13
- **Deciders**: Mahdi Amirabdollahi
- **Supersedes**: the "never edit a released changeset" convention, until the first stable release

## Context

Every UUID key in the schema defaults to the random `gen_random_uuid()`. Random keys scatter inserts
over the whole primary-key index, which means page splits, a bloated index and a cold cache on the
tables that grow fastest. Several tables have no primary key at all (`metric_sample`, `storage_sample`,
`flow_route`, `flow_client_edge`), twenty foreign keys have no index to serve their joins and cascades,
`inbox_item.data` is `json`, and the `pgcrypto` extension is installed for a function PostgreSQL 18
ships in core. Nothing checks the schema against any rule, so each of these arrived unnoticed.

Fixing the shapes means editing tables that are already released, and the changesets that created them
are immutable (ADR-0008). Studio is pre-stable, and ADR-0072 re-baselined the history once already, as a
recorded exception, for the same reason.

Liquibase also serialises migrations with a row in `DATABASECHANGELOGLOCK`. A replica that dies in the
middle of a migration leaves that row locked, and every later boot waits on it until an operator clears
it by hand.

## Decision

- **Keys are UUIDv7.** Every UUID primary key defaults to `uuidv7()`, which PostgreSQL 18 provides in
  core, so inserts append at the right edge of the key index. A key that is also a foreign key takes its
  parent's id and has no default. A bearer handle (`sql_query_ticket`, `diagnostics_snapshot`) has no
  default and stays a random UUIDv4, because a time-ordered value is partly guessable. A `bigint`
  identity remains only where the number is a cursor or an order.
- **The history is re-baselined a second time.** Each module's changelog is one
  `changes/0001-baseline.sql` with the final shapes, built from a `pg_dump --schema-only` of the previous
  schema. The deliberate differences are:
  - every table has a primary key: `storage_sample` on (`schema_name`, `table_name`, `sampled_at`),
    `flow_route` and `flow_client_edge` on their identity index, `metric_sample` on its lookup columns
    (its writer skips a repeated sample with `ON CONFLICT DO NOTHING`);
  - every foreign key leads some index;
  - `inbox_item.data` is `jsonb`, and `pgcrypto` is gone;
  - tables that lacked them carry storage parameters for their class: append-only history, hot-update
    state, or a singleton row;
  - no SQL names `public`: the schema is whichever one the connection selects.
- **Until the first stable release a change edits the baseline.** The "never edit a released changeset"
  rule of ADR-0008 and ADR-0072 is suspended, and operators recreate the database with each release that
  changes the schema. The roadmap change `36-stable-release` restores the rule together with the
  compatibility policy.
- **A schema lint guards the rules.** `SchemaLintTest` reads the catalog of the migrated database and
  fails on: `json`, `timestamp` without time zone or `varchar(n)` columns; a `gen_random_uuid()` or
  serial default; a table without a primary key; a UUID key whose default is not `uuidv7()`; a `bigint`
  key that is not an identity; a foreign key no index leads with; and a default partition. One allow-list
  names each exception with its reason (ShedLock and Spring Session tables, the bearer handles), and an
  entry the schema no longer needs fails the test, so the list holds only live exceptions.
- **Liquibase takes a session advisory lock.** `liquibase-sessionlock` replaces the lock row with
  `pg_try_advisory_lock` on the migrating connection, so a replica that dies releases the lock with its
  connection.

## Consequences

- **BREAKING:** an existing database cannot be upgraded. Operators recreate it and register clusters,
  users, tokens, channels and rules again; the release note says so.
- Inserts into every UUID-keyed table append to the index. Application code that needs an id before the
  insert takes it from one shared UUIDv7 source (a separate change).
- A new table that breaks a rule fails the standard test run, naming the table and the rule.
- A crashed replica no longer wedges later boots. The lock needs a session-stable connection: a pooler
  in transaction mode would drop it.
- Editing the baseline is easy until the stable release and then stops being allowed, so every shape that
  should change before that must change now.
- `metric_sample` keeps one sample per subject, metric and instant: two nodes of one cluster that report
  the same queue in the same instant keep the first.

## Alternatives considered

- **New changesets that alter the tables in place.** Existing installs keep upgrading, but the history
  keeps the random keys, the missing keys and several changesets per table that nothing needs to replay.
  Rejected while no stable release exists, as in ADR-0072.
- **A UUID library for version 7.** It adds a dependency for what Hibernate and PostgreSQL already ship.
- **UUIDv7 for bearer handles too.** A ticket or snapshot id is a credential, and the timestamp in a v7
  value narrows a guess.
- **A hand-written `LockService`.** The library already does this for PostgreSQL, Oracle, MySQL and SQL
  Server and is picked up by Liquibase's service loader.
