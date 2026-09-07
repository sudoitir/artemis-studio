# Artemis Studio

**One console for every Apache ActiveMQ Artemis cluster you run.**

The bundled Hawtio console manages one broker at a time and has no idea a cluster
exists. Artemis Studio is the other thing: one instance across many clusters —
live/backup topology, cross-node queues and addresses in a single table, safe
message operations, first-class request-reply tracing, and SQL over your
messages.

It works against your **existing** brokers. No `broker.xml` rewrite beyond
enabling the management endpoints you almost certainly already run.

> **⚠️ Alpha.** Under active development, not yet feature-complete. These images are
> **pre-stable dev builds** — the `:dev` tag is the moving pointer and there is no
> `:latest` yet. Expect breaking changes.

**Documentation:** [sudoitir.github.io/artemis-studio](https://sudoitir.github.io/artemis-studio/)
&nbsp;·&nbsp; **Source and issues:**
[github.com/sudoitir/artemis-studio](https://github.com/sudoitir/artemis-studio)


## Screenshots

| Cluster topology | Cross-node queues |
|---|---|
| [![Live/backup topology with replication and shared-NodeID axis](https://raw.githubusercontent.com/sudoitir/artemis-studio/main/docs/img/topology.png)](https://raw.githubusercontent.com/sudoitir/artemis-studio/main/docs/img/topology.png) | [![Every queue across every node in one virtualized grid](https://raw.githubusercontent.com/sudoitir/artemis-studio/main/docs/img/queues.png)](https://raw.githubusercontent.com/sudoitir/artemis-studio/main/docs/img/queues.png) |
| **Metrics and charts** | **Governance (RBAC, environments, tokens, SSO)** |
| [![Depth, throughput and consumer charts from partitioned Postgres](https://raw.githubusercontent.com/sudoitir/artemis-studio/main/docs/img/metrics.png)](https://raw.githubusercontent.com/sudoitir/artemis-studio/main/docs/img/metrics.png) | [![Users, scoped grants, environments, API tokens and OIDC claim mapping](https://raw.githubusercontent.com/sudoitir/artemis-studio/main/docs/img/governance.png)](https://raw.githubusercontent.com/sudoitir/artemis-studio/main/docs/img/governance.png) |

---

## Run it

Studio and its Postgres. Your Artemis clusters already exist and are registered
through the UI — nothing here starts a broker.

```bash
base=https://raw.githubusercontent.com/sudoitir/artemis-studio/main/deploy/compose
curl -sO "$base/compose.prod.yaml"
curl -s "$base/.env.example" -o .env   # then edit: see the table below
docker compose -f compose.prod.yaml --env-file .env up -d
```

Or a bare container against your own Postgres:

```bash
docker run -p 8080:8080 \
  -e ARTEMIS_STUDIO_DB_URL=jdbc:postgresql://db:5432/artemis_studio \
  -e ARTEMIS_STUDIO_DB_USER=artemis_studio \
  -e ARTEMIS_STUDIO_DB_PASSWORD=... \
  -e ARTEMIS_STUDIO_SECRET_KEY="$(openssl rand -base64 32)" \
  sudoit1/artemis-studio:dev
```

| Variable | Required | Notes |
|---|---|---|
| `ARTEMIS_STUDIO_DB_URL` | yes | `jdbc:postgresql://host:5432/artemis_studio` |
| `ARTEMIS_STUDIO_DB_USER` / `_DB_PASSWORD` | yes | — |
| `ARTEMIS_STUDIO_SECRET_KEY` | yes | Encrypts stored broker credentials. Base64 of **exactly 32 bytes** or the app will not start: `openssl rand -base64 32` |
| `ARTEMIS_STUDIO_CONFIG_ENCRYPT_KEY` | no | Decrypts `{cipher}` values in `studio_config_property`. A **different** key from `ARTEMIS_STUDIO_SECRET_KEY` — do not reuse it |
| `JAVA_OPTS` | no | Defaults to `-XX:MaxRAMPercentage=75` |

**First login.** Username `admin`. The first run against an empty database
generates the password and prints it **once** to the container log:

```bash
docker compose -f compose.prod.yaml logs studio | grep -A4 'Created administrator'
```

You will be forced to set a new password on first login. Lose that first password
before changing it and the only recovery is resetting the row directly in
Postgres — there is no password-reset flow yet.

**Reverse proxy.** The live UI stream is SSE at `GET /api/v1/stream`; a proxy in
front of Studio **must not buffer it** (nginx `proxy_buffering off;`, Apache no
output buffering on that path; Traefik works as-is). Without this the topology
graph and queue grid only update on the 5-second poll.

## Tags

| Tag | What it is |
|---|---|
| `:dev` | the newest build; moves with every push to `main` |
| `:YYYY.MM` | the newest build of that calendar month; moves within it |
| `:YYYY.MM.N` | one exact release; **immutable**, never republished |

`linux/amd64` and `linux/arm64`. Versioning is CalVer and the number carries no
compatibility promise — a release's breaking changes are listed at the top of its
[release notes](https://github.com/sudoitir/artemis-studio/releases).

## Licence

Apache-2.0.

"Apache", "ActiveMQ" and "Artemis" are trademarks of the Apache Software
Foundation. This project is not affiliated with or endorsed by the ASF; the name
is used to describe what the software works with.
