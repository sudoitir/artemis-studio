# ADR-0011: Persistence via JPA (Hibernate) mapped to the Liquibase-owned schema, not Spring Data JDBC

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi

## Context

`docs/architecture.md` lists the persistence layer as "Spring Data JDBC +
Liquibase", and `spring-boot-starter-data-jdbc` was on the classpath. Phase 1 is
the first code that reads and writes the estate tables (`cluster`, `broker_node`,
`broker_credential`, `broker_tls`, `audit_event`).

Two facts rule out Spring Data JDBC here:

1. **Aggregate persistence reassigns child identity.** Spring Data JDBC's
   `save()` on an aggregate root with a collection of children deletes the
   existing child rows and re-inserts them. `broker_node` rows would be children
   of a `cluster` aggregate and are referenced by `audit_event.node_id`
   (`ON DELETE SET NULL`). A refresh tick that re-saved the cluster would churn
   every node's UUID every few seconds and quietly null out audit references.
2. **The refresh path needs a scoped update**, not a whole-aggregate rewrite:
   `broker_node.active / state / replica_sync / observed_cycle` change on every
   tier-A cycle while the row's identity must stay put.

The initial Phase 1 draft used `JdbcClient` with hand-written SQL repositories.
That was reverted before merge: hand-maintained SQL strings for every read and
write, hand-written `RowMapper`s, and hand-rolled upserts are a large, error-prone
surface for a schema this ordinary, and the "no ORM" saving is small once the
mappers exist.

## Decision

- **Use JPA (Hibernate, via `spring-boot-starter-data-jpa`)** with
  `@Entity` classes and Spring Data `JpaRepository` interfaces.
- **Liquibase remains the single schema source of truth.** Hibernate never
  emits DDL: `spring.jpa.hibernate.ddl-auto=validate` checks the entity mapping
  against the migrated schema on startup and fails fast on drift. ADR-0008
  stands; the XML changelog is unchanged.
- **Entities map to the existing columns as-is.** The padding-ordered column
  layout is a physical concern Hibernate does not care about; `@Column(name=...)`
  pins each field. `broker_node`/`cluster`/`broker_tls`/`broker_credential` use
  `@GeneratedValue(strategy = UUID)`; `audit_event.id` uses
  `GenerationType.IDENTITY` to match `GENERATED ALWAYS AS IDENTITY`.
  `audit_event.params` (`jsonb`) maps with `@JdbcTypeCode(SqlTypes.JSON)`.
- **Updates are dirty-checking, not `DELETE`+`INSERT`.** Load the managed
  `BrokerNodeEntity`, set the changed fields inside a `@Transactional` method,
  let the flush issue `UPDATE ... WHERE id = ?`. Row identity is stable, so
  `audit_event.node_id` references survive every refresh.
- **`open-in-view=false`.** No lazy loading outside a transaction; the web layer
  maps entities to DTOs inside the service call.
- **Audit is written in the command's own transaction** (non-negotiable #3):
  `AuditService` persists a `PENDING` row before the broker call and updates the
  same managed entity to `SUCCESS` / `FAILURE` after, all in one transaction.

## Consequences

- Derived query methods (`findByClusterId`, `findByClusterIdAndName`) replace
  hand-written `SELECT`s; the few genuinely custom updates use `@Query` JPQL,
  which is portable and schema-checked, not raw SQL.
- `ddl-auto=validate` turns a mapping/schema mismatch into a startup failure,
  caught by the existing Testcontainers context test.
- Hibernate is a heavier dependency than `JdbcClient`. Accepted: the mapping and
  transaction machinery it provides is exactly what the audit-in-transaction and
  identity-stable-refresh requirements need, and it removes far more code than it
  adds.
- One risk to watch: a second place (entity annotations) now describes the
  schema. `validate` keeps the two honest, and released changesets are still
  immutable (ADR-0008).

## Alternatives considered

- **Spring Data JDBC repositories.** The child-row delete/reinsert behaviour and
  the `IDENTITY` primary key on `audit_event` make it actively harmful for the
  refresh loop. Rejected.
- **`JdbcClient` + hand-written repositories.** The Phase 1 starting point.
  Works, but every query and row mapping is hand-maintained; rejected as
  needless surface area for an unremarkable schema.
- **jOOQ / MyBatis.** Capable, but a new dependency and a code-gen or
  mapping-file step. Rejected.
