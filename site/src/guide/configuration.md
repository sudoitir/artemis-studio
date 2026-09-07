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
| `JAVA_OPTS` | no | Defaults to `-XX:MaxRAMPercentage=75` |

`ARTEMIS_STUDIO_SECRET_KEY` is not rotatable in place: it is the key every stored
broker credential was encrypted with. Losing it means re-entering every
connection's credentials.

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

## Broker connections

Registered through the UI, not the environment. A connection stores a seed
management endpoint and credentials, encrypted at rest. Jolokia HTTP is the
primary transport; the Artemis Core client is a second channel used for
notifications and faithful message I/O, and features that need it are gated on
it being reachable — visibly, with the `broker.xml` that would enable it.

See [ADR-0002](/reference/adr/0002-broker-transport-and-capability-model).
