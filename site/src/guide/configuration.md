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
| `ARTEMIS_STUDIO_SECRET_KEY` | with the `env` provider | The key that protects every stored secret. Base64 of **exactly 32 bytes**, or the application will not start: `openssl rand -base64 32`. To hold several versions, see [Secrets and key rotation](#secrets-and-key-rotation) |
| `ARTEMIS_STUDIO_CAPTURE_BROKER_ROLE` | for capture | The broker role Studio's own broker user holds. Capture queues are restricted to it, and message capture is refused until it is set. Use a dedicated role, not the default `amq` |
| `ARTEMIS_STUDIO_CONFIG_ENCRYPT_KEY` | no | Decrypts `{cipher}` values stored in `studio_config_property`. A **different** key from `ARTEMIS_STUDIO_SECRET_KEY` — do not reuse it |
| `ARTEMIS_STUDIO_PUBLIC_URL` | no | The address operators reach Studio at, e.g. `https://studio.example.com`. Alert notifications link back to the cluster's alerts when it is set; see [Alert delivery](./alert-delivery) |
| `JAVA_OPTS` | no | Defaults to `-XX:MaxRAMPercentage=50` |

## Secrets and key rotation

Every stored secret is sealed under its own random data key, and that data key is wrapped by a
versioned **key-encryption key** (KEK). The secrets are broker and bridge credentials,
notification channel secrets, plugin secrets and the sealed message originals of governance.
A key provider supplies the KEKs. It is chosen once, at startup, and there is no fallback: if the
provider cannot deliver a valid key, Studio does not start and names the provider.

### Providers

`artemis-studio.secrets.provider` selects one. In an environment variable a dash becomes an
underscore and a dot becomes an underscore, so `artemis-studio.secrets.vault.uri` is
`ARTEMIS_STUDIO_SECRETS_VAULT_URI`.

| Provider | Where the keys live | Settings (default in brackets) |
|---|---|---|
| `env` (default) | `ARTEMIS_STUDIO_SECRET_KEY`: a bare base64 key (version 1), or `1=<b64>,2=<b64>` for several versions | none |
| `file` | A directory of files `kek-<n>` (base64 of 32 bytes; `n` is the version). A mounted Kubernetes Secret volume fits | `artemis-studio.secrets.file.directory` |
| `vault` | HashiCorp Vault KV version 2. The field `kek` of each live KV version is a key version | `artemis-studio.secrets.vault.uri`, `.mount` [`secret`], `.path`, `.oidc-path` [the same as `.path`], `.authentication` [`token`; or `approle`, `kubernetes`], `.token`, `.role-id`, `.secret-id`, `.kubernetes-role`, `.kubernetes-token-path` [`/var/run/secrets/kubernetes.io/serviceaccount/token`] |
| `kubernetes` | One Kubernetes Secret with keys `kek-<n>`, read through the API server with the pod's service account | `artemis-studio.secrets.kubernetes.secret-name`, `.namespace` [the pod's own], `.api-url` [`https://kubernetes.default.svc`], `.token-path` [`/var/run/secrets/kubernetes.io/serviceaccount/token`], `.ca-path` [`/var/run/secrets/kubernetes.io/serviceaccount/ca.crt`] |

The service account of the `kubernetes` provider needs `get` on that one Secret.

Every key must be base64 of exactly 32 bytes. The version of a key is the number in its name
(`kek-2`, or `2=` in the environment variable, or the KV version in Vault).

### Vault keeps few versions by default

KV version 2 keeps only **10 versions** of a secret and deletes the oldest when an eleventh is
written, and every write counts, even one that changes nothing but `oidc-client-secret`. Losing a
version that stored secrets are still wrapped under makes them unreadable. So:

- Set `max_versions` on the path high (for example `vault kv metadata put -max-versions=0 secret/artemis-studio`,
  where `0` means unlimited).
- Keep `oidc-client-secret` on a separate path with `artemis-studio.secrets.vault.oidc-path`, so
  editing it never adds a version to the key path.
- Never delete or destroy a version that is still in use. **Settings → Security** lists a version
  that stored secrets use but the provider lacks as missing, and the log warns with its version and
  count.

### The OIDC client secret

The provider also supplies the OIDC client secret, under the name `oidc-client-secret`: the file or
Kubernetes Secret key of that name, the field `oidc-client-secret` of the latest version at `.oidc-path` in Vault, or
`ARTEMIS_STUDIO_OIDC_CLIENT_SECRET` with the `env` provider. Configure it there and nowhere else:
a client secret set in Studio's own OIDC settings is an error when the provider is not `env`.

### Rotating the key

Rotation is online. It moves every secret to a new key version without downtime and without ever
reading a secret in the clear.

1. Add a new version to the provider (`kek-2`, `1=…,2=…`, or a new KV version). **Keep the old one.**
2. In **Settings → Security**, choose **Rotate key**. It needs the `settings:write` permission and
   a recent sign-in. The same is `POST /api/v1/settings/secrets/rotations`.
3. New secrets use the new version at once. A background job re-wraps the rest in batches. Watch
   the rotation until it reaches **Succeeded**: it waits at least 30 seconds after the start so
   that every replica has learned the new version, and it is safe to restart Studio meanwhile.
4. Remove the old key from the provider only after that. **Security** counts the rows under each
   version, so you can see when an old version is unused.

If a rotation fails (its error names the store and row), keep the old keys in the provider, fix the
cause and start it again: it re-wraps only what is still under an older version. Removing an old key
too early makes the secrets under it unreadable, and the only way back is to re-enter them.

### Changing provider

1. Put the current key into the new provider under a version number **at least as high** as the current one.
2. Change `artemis-studio.secrets.provider` (and its settings) and restart. Studio starts only if the new provider holds the
   current version.
3. Add a newer version to the new provider and rotate to it, as above. Only then retire the old
   provider.

### Upgrading from an earlier version

Secrets stored by earlier versions are dropped on the first start, because the storage format
changed. Re-enter broker credentials (clusters and bridges), notification channel secrets and
plugin secrets. Governance originals sealed earlier are no longer available. Nothing else is lost.

### Redaction

Passwords, tokens, API keys, bearer values, URL user-info and private keys are masked as
`[redacted]` in logs, audit parameters, error responses and the broker.xml export, even when a
secret arrives by a route Studio did not expect. Studio logs to the console only.

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
