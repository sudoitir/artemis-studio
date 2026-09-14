---
title: Configuration
description: The environment variables Artemis Studio reads, which are required, and the two configuration planes it keeps.
---

# Configuration

## Environment

| Variable | Required | Notes |
|---|---|---|
| `ARTEMIS_STUDIO_DB_URL` | yes | `jdbc:postgresql://host:5432/artemis_studio` |
| `ARTEMIS_STUDIO_DB_USER` / `_DB_PASSWORD` | yes | — |
| `ARTEMIS_STUDIO_SECRET_KEY` | yes | Encrypts stored broker credentials. Base64 of **exactly 32 bytes**, or the application will not start: `openssl rand -base64 32` |
| `ARTEMIS_STUDIO_CONFIG_ENCRYPT_KEY` | no | Decrypts `{cipher}` values stored in `studio_config_property`. A **different** key from `ARTEMIS_STUDIO_SECRET_KEY` — do not reuse it |
| `JAVA_OPTS` | no | Defaults to `-XX:MaxRAMPercentage=50` |

`ARTEMIS_STUDIO_SECRET_KEY` is not rotatable in place: it is the key every stored
broker credential was encrypted with. Losing it means re-entering every
connection's credentials.

## Features

Every optional feature can be turned off at startup, and is on unless you do:

```bash
ARTEMIS_STUDIO_FEATURES_SQL_ENABLED=false        # artemis-studio.features.sql.enabled
```

The ids are `queues`, `resources`, `messages`, `routing`, `metrics`, `alerting`,
`events`, `rr`, `sql`, `brokerconfig`, `triage`, `mcp`, `apitokens`, `identity-local`
and `identity-oidc`; in an environment variable a dash becomes an underscore. A disabled
feature has no screens, no API and no assistant tools: its navigation entry is gone, its
address explains that it is off and names this property, and its API answers
`404 feature-disabled`. Its tables are still migrated, so turning it back on is a
restart. The kernel and platform (clusters, brokers, scrape, security, audit, settings,
stream) cannot be turned off, and a feature another one `requires` cannot be turned off
while that one is on — Studio refuses to start and says which.

`GET /api/v1/manifest` lists what this installation has enabled.

## The two planes

Configuration is deliberately split in two, and the split is about who changes a
value and when.

**The operator plane** — `studio_setting` — holds what an operator tunes while
Studio is running: poll cadences, retention windows, the bulk-operation cap, the
SQL Console's cost ceiling. Changes take effect live, are audited, and need no
restart. Every settings-tunable schedule is a re-reading trigger, not a fixed
annotation, which is why a cadence change is immediate.

**The deploy plane** — Spring Cloud bootstrap properties — holds what belongs to
the deployment: the datasource, the keys, the OIDC issuer. Values here can be
stored as `{cipher}` and decrypted with `ARTEMIS_STUDIO_CONFIG_ENCRYPT_KEY`.
There is no configuration server.

See [ADR-0047](/reference/adr/0047-two-configuration-planes) for why, and
[ADR-0048](/reference/adr/0048-settings-driven-dynamic-schedules) for how a
schedule picks up a changed setting.

## Database

PostgreSQL, with the schema owned by Liquibase and migrated on startup. Studio
validates the mapping against the migrated schema at boot rather than generating
DDL, so a schema that has drifted fails loudly instead of quietly.

Postgres owns configuration, users and the audit trail. The broker-derived
tables — `queue_snapshot`, `metric_sample` — are a **disposable cache**: losing
them costs you history, never truth.

## Monitoring

`/actuator/prometheus` exports what an operator needs to tell whether Studio itself is
healthy and whether it is loading a broker:

| Metric | Meaning |
|---|---|
| `jvm_threads_live_threads` | Live threads in Studio. Steady in normal operation. |
| `studio_broker_requests_total{node}` | Management requests Studio issued to one node. |
| `studio_broker_permit_wait_seconds{node}` | Time requests waited for that node's rate ceiling. |
| `studio_broker_permit_timeouts_total{node}` | Requests refused because the ceiling stayed full for 5 seconds. |

Recommended alerts:

- **Thread growth** — `jvm_threads_live_threads` above a few hundred, or rising steadily
  for an hour. Studio's threads are bounded by configuration, not by time.
- **Studio at the broker ceiling** — the rate of `studio_broker_requests_total` for a node
  close to `ARTEMIS_STUDIO_RATE_LIMIT_MANAGEMENT_CALLS_PER_SECOND`, or permit wait time
  rising. Studio is calling that node as fast as it is allowed to; the ceiling is doing its
  job, but views of that node will lag.
- **Permit timeouts** — any increase of `studio_broker_permit_timeouts_total`. A request
  was refused rather than queued without end; check what is holding that node's ceiling.

## Broker connections

Registered through the UI, not the environment. A connection stores a seed
management endpoint and credentials, encrypted at rest. Jolokia HTTP is the
primary transport; the Artemis Core client is a second channel used for
notifications and faithful message I/O, and features that need it are gated on
it being reachable — visibly, with the `broker.xml` that would enable it.

See [ADR-0002](/reference/adr/0002-broker-transport-and-capability-model).
