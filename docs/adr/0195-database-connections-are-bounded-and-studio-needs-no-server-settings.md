# ADR-0195: Database connections are bounded, and Studio needs no server settings

- **Status**: accepted
- **Date**: 2026-10-13
- **Deciders**: Mahdi Amirabdollahi

## Context

Studio's pool used HikariCP's defaults: ten connections, a 30 s wait for one, no statement timeout and
no driver timeouts. A runaway query held a connection for as long as it liked, a network partition
could hang a thread forever, and a request that found the pool empty queued behind every other.
With virtual threads a long wait costs nothing to start and piles up without end.

The bundled Postgres was tuned by three drifting lists of `-c` flags, one per compose file, and the
tuning file beside them was only a reference. Operators also run Studio against a managed or HA
PostgreSQL 18 that Studio does not administer, where none of that tuning exists.

## Decision

1. **A connection budget.** Each replica has a pool of 16 (`ARTEMIS_STUDIO_DB_POOL_SIZE`), and the pool
   waits at most 5 s for a connection, recycles one after 30 min, probes an idle one every 2 min and
   logs a connection held past 120 s. Replicas together, with their listening connection and the
   plugin pools (three each), must stay under the server's `max_connections`; the bundled file sets 150.
2. **Limits are set on every connection**, by Hikari's `connection-init-sql`: `statement_timeout=30s`,
   `lock_timeout=5s`, `idle_in_transaction_session_timeout=60s`. They are session settings, so they hold
   on any server, through a session-mode pooler (a startup `options` parameter is rejected by some
   poolers) and after a failover, because a new connection runs them again. The driver adds
   `connectTimeout=5`, `socketTimeout=900` and `tcpKeepAlive`. The socket timeout is above the longest
   statement timeout, so only a dead peer trips it. Plugin pools take the same pool limits and driver
   properties with their own 60 s statement timeout.
3. **Long work raises the timeout for one transaction.** The SQL console, the audit export, the
   lifecycle preview and partition maintenance run `SET LOCAL statement_timeout` inside their own
   transaction (`StatementTimeout`), ten minutes at most, so no other connection and no later
   transaction inherits it.
4. **One configuration for the bundled database.** `deploy/postgres/postgresql.conf` is a complete file
   that the production, HA and development compose files mount read-only and load with
   `-c config_file`. It adds to the earlier tuning statement statistics (`pg_stat_statements` with
   `compute_query_id`, created by an init script), lock-wait logging, UTC, `io_method=worker` set
   explicitly because Docker's default seccomp profile blocks `io_uring`, autovacuum logging,
   `max_connections=150` and a five-minute idle-in-transaction backstop for other clients.
5. **Studio never depends on a server setting.** Everything it needs is set per connection (2, 3), and no
   extension is required. The shipped file is the recommended baseline for an external server, not a
   prerequisite.

## Consequences

- A stuck query, a partitioned network and an exhausted pool each fail within seconds, with an error,
  instead of hanging.
- A new feature with a legitimately long statement must raise its own timeout; forgetting shows up as
  a cancelled query, not a stall.
- The 15 min socket timeout bounds how long a dead peer holds a thread unless the operating system's
  keepalive notices it sooner.
- Quickstart users download the configuration file next to the compose file; the compose file
  mounts it from `../postgres`.
- The migration runs on the same pool, so a single migration statement is cut at 30 s unless it raises
  its own timeout.

## Alternatives considered

- **pgJDBC `options=-c ...`:** one startup parameter, but PgBouncer rejects it unless told to ignore it and
  RDS Proxy pins the connection on it.
- **`ALTER ROLE` or `ALTER DATABASE ... SET`:** a server-side setting, which an operator's managed database
  may not allow and which Studio would then silently lack.
- **A pool wait of 30 s:** hides saturation; failing fast shows it.
- **Keeping the inline `-c` lists:** they had already drifted from one another.
