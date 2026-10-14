# Running Studio on an external PostgreSQL

Studio runs against a PostgreSQL 18 it does not administer: a cloud service, a Patroni or in-house
cluster, or a server shared with other applications. It treats the server as a black box and needs
nothing of it beyond what is on this page. The decision is
[ADR-0199](/reference/adr/0199-studio-runs-on-an-external-postgresql).

The bundled database (`compose.prod.yaml`, `just up`) is the same thing with a known configuration, and
needs none of this.

## Version

PostgreSQL **18 or later**. Studio generates its keys with the built-in `uuidv7()`. It needs no extension:
`pg_stat_statements` is optional and only feeds the storage view.

## Privileges and schema

Studio connects as an ordinary role, not a superuser. The role needs:

- **`CREATE` on the database**, because every plugin keeps its tables in a schema of its own;
- **`USAGE` and `CREATE` on Studio's schema**, which it owns, so it can create tables and indexes, set
  storage parameters, `VACUUM` them and manage their partitions.

```sql
CREATE ROLE studio LOGIN PASSWORD '...' NOSUPERUSER;
GRANT CREATE ON DATABASE studio TO studio;
CREATE SCHEMA studio AUTHORIZATION studio;
```

Studio's tables live in the connection's **current schema**, which you choose with `currentSchema` in the
JDBC URL. Nothing in Studio names `public`.

```
ARTEMIS_STUDIO_DB_URL=jdbc:postgresql://db.example.com:5432/studio?currentSchema=studio
```

At boot, before it migrates anything, Studio checks the version, the privileges and the connection (see
[poolers](#poolers)) and refuses to start with a message that names the fix. It only warns, and shows the
warning under the `database` entry of the `studio` health group, when the server cannot hold its
connection budget or has timeouts below Studio's own.

## TLS

Use `sslmode=verify-full` so the connection is encrypted and the server's certificate and host name are
checked. Give the driver the CA that signed it:

```
jdbc:postgresql://db.example.com:5432/studio?currentSchema=studio&sslmode=verify-full&sslrootcert=/etc/studio/db-ca.pem
```

## Poolers

Use **session mode** or connect directly. Studio relies on state that lives as long as a connection:
session advisory locks (cluster duties, plugin activation), `LISTEN` for the messages replicas send each
other, and per-connection limits (`statement_timeout`, `lock_timeout`, `idle_in_transaction_session_timeout`).
PgBouncer in transaction mode and RDS Proxy when it pins sessions hand a different server connection to
each transaction, so a lock is released behind Studio's back and a `SET` is gone. The boot check runs two
transactions on one connection and refuses a database where they do not share a backend and a session
setting.

## Failover

Give the driver every host and ask for the primary:

```
jdbc:postgresql://db1.example.com:5432,db2.example.com:5432/studio?currentSchema=studio&targetServerType=primary
```

After a failover the pool drops the broken connections and reconnects to whichever host is primary, with no
restart. Requests fail fast during the switch (a connection is waited for at most 5 s) and succeed again
afterwards. Everything that lives in a session treats a dropped connection as lost:

- the listening connection reconnects with a backoff and tells the replica to reload what it caches;
- a cluster lock probes its connection while its work runs, and stops the work when the connection is gone;
  the next pass takes the lock again, if it is free;
- locks kept in tables (job locks, cluster ownership) survive a failover unchanged.

## Read replica

Heavy history reads and exports can run on a standby. Set a second, read-only pool:

| Property | Default | Meaning |
|---|---|---|
| `studio.database.read.url` | none | The standby's JDBC URL. Unset means every read uses the primary |
| `studio.database.read.username` / `.password` | the primary's | The standby's credentials |
| `studio.database.read.pool-size` | `8` | Connections each Studio replica keeps to the standby |
| `studio.database.read.max-lag` | `30s` | The most the standby may lag and still serve a read |

Before using the standby Studio measures its lag (`now() - pg_last_xact_replay_timestamp()`, remembered for
five seconds). Past `max-lag`, or when the standby cannot be reached, the read goes to the primary. A
response served from the standby says that it may lag. Writes, locks and anything that reads what the same
request has just written always use the primary. An idle primary replays nothing, so a standby behind an
idle primary can look lagged; the read then goes to the primary, which is correct, only slower.

A physical standby has the primary's roles and data, so the same role reads Studio's schema. Studio starts
without waiting for the standby, so one that is down at boot only means reads use the primary.

## Connection budget

Every Studio replica holds, against the **primary**:

```
pool (ARTEMIS_STUDIO_DB_POOL_SIZE, 16)  +  1 listening connection  +  3 per active plugin
```

All replicas together must stay under `max_connections` minus the connections the server reserves for
itself (`superuser_reserved_connections`, `reserved_connections`) and everything else that uses the server.
The read pool (`studio.database.read.pool-size`, per Studio replica) counts against the **standby's** limit
in the same way. Studio warns at boot when one replica alone does not fit, and refuses to activate a plugin
that would take more than 80% of `max_connections`.

Two replicas with three plugins: `2 x (16 + 1 + 3 x 3) = 52`.

## Recommended server settings

Studio sets its own limits on each connection and works with any server configuration. The bundled
database's file, [`deploy/postgres/postgresql.conf`](https://github.com/sudoitir/artemis-studio/blob/main/deploy/postgres/postgresql.conf),
is the baseline Studio is tuned and tested with: autovacuum that keeps up with the scrape writes,
`wal_compression`, `pg_stat_statements`, lock-wait logging and UTC. Apply what your service lets you set.

A server default `statement_timeout` or `idle_in_transaction_session_timeout` below Studio's (30 s and 60 s)
is overridden by Studio's per-connection setting, and is reported as a warning because a proxy that resets
session settings would bring it back.
