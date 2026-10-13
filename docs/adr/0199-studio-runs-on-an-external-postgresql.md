# ADR-0199: Studio runs on an external PostgreSQL 18 it does not administer

- **Status**: accepted
- **Date**: 2026-10-13
- **Deciders**: Mahdi Amirabdollahi

## Context

Operators run Studio against a managed or highly available PostgreSQL (a cloud service, Patroni, an
in-house cluster) that Studio does not control: other tenants, its own server settings, a schema of the
operator's choosing, replicas, failovers and a connection limit shared with others. Studio so far assumed
the bundled database: the `public` schema, a superuser, a pgcrypto extension, a direct connection that
never moves, and a pool nobody counted.

Several things in Studio live in a database session: the `LISTEN` connection the replicas talk over
(ADR-0152), the advisory locks that give each cluster one worker, and the plugin activation lock. A
pooler in transaction mode or a failover silently ends such a session.

## Decision

1. **Any schema, no superuser, no extension.** Core tables live in the connection's current schema
   (`currentSchema` in the JDBC URL) and no SQL names `public`. Studio needs a role that owns its schema
   and holds `CREATE` on the database (plugins get a schema each). An integration test migrates and boots
   Studio in a non-default schema as such a role.
2. **A preflight before Liquibase** (`DatabasePreflight`). It fails the boot with a message that names the
   fix when the server is older than 18, a privilege is missing, or the connection is not session-stable
   (two transactions on one connection must share a backend and a session setting, which transaction-mode
   pooling breaks). It warns, and degrades the `database` health contributor, when the server cannot hold
   one replica's pool and listening connection, or has timeouts below Studio's.
3. **Multi-host URLs with `targetServerType=primary`** are the failover mechanism. Every session-scoped
   resource treats a dropped connection as lost: the listening connection reconnects with a backoff and
   publishes a resync signal, and a cluster lock probes its connection while its work runs and interrupts
   the work when the connection is gone, so the duty stops until the next pass takes the lock again.
   Table-based locks and the partition maintainer's transaction-scoped locks need nothing.
4. **An optional read replica** (`studio.database.read.*`): a second, read-only pool behind one API,
   `ReadRouting`, that runs a callback in a read-only transaction on the standby while its replay lag is
   within a bound (30 s by default, measured and cached for 5 s), and on the primary otherwise or when the
   standby fails. The result says which one served it, so a response can state that it may lag. Writes,
   locks and read-after-write never use it.
5. **The plugin host's connection budget counts the configured pool** instead of a fixed 16.
6. **`docs/external-database.md`** is the operator's guide: version, privileges, schema, TLS, poolers,
   failover URLs, the replica and the connection budget.

## Consequences

- Studio runs on managed PostgreSQL 18 without superuser rights, and a misconfigured database is refused at
  boot with the fix named, instead of failing later in a migration or a lock.
- Transaction-mode pooling is refused. Operators who must pool use session mode or connect directly.
- A cluster duty that loses its lock is interrupted, so its work must tolerate an interrupt. The lock is
  free again for whichever instance holds the primary connection next.
- A read served by the standby may lag by up to the bound, and callers must say so.
- The preflight reads the server's settings, which differ per provider; the warnings are advice, not a
  guarantee, because Studio's own per-connection limits (ADR-0195) apply regardless.

## Alternatives considered

- **Requiring a superuser or the bundled database:** simpler, but excludes every managed service.
- **Detecting a transaction-mode pooler by name or port:** fragile. Observing the session is exact.
- **Replacing session advisory locks with lease rows everywhere:** survives any pooler, but each lock would
  need a heartbeat and expiry for work that today ends with the connection. Rejected for now.
- **Routing every read through the standby:** a read after a write would miss its own write.
