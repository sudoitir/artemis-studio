---
title: Quickstart
description: Run Artemis Studio and its Postgres from the published image, register your first cluster, and sign in.
---

# Quickstart

Studio needs two things: a PostgreSQL database, and network reach to your
brokers' management endpoints. Your Artemis clusters already exist and are
registered through the UI — nothing here starts a broker.

::: warning Alpha
Published images are pre-stable dev builds (`sudoit1/artemis-studio:dev`; no
`:latest` is published yet). Expect breaking changes between releases.
:::

## With `just`, from a clone

[`just`](https://github.com/casey/just#packages) is the task runner this repo
uses. `just` on its own lists every task, grouped.

```bash
git clone https://github.com/sudoitir/artemis-studio && cd artemis-studio
just up
```

`just up` runs `just setup` first: on a clean checkout it writes
`deploy/compose/.env` with a random `SECRET_KEY` and `DB_PASSWORD`, and pins
`STUDIO_IMAGE` to the newest published release tag. Then it starts Studio and
Postgres and waits until Studio answers. Open <http://localhost:8080>.

Run `just setup` again later to move the version pin forward.

## Without `just`

```bash
base=https://raw.githubusercontent.com/sudoitir/artemis-studio/main/deploy/compose
curl -sO "$base/compose.prod.yaml"
curl -s "$base/.env.example" -o .env   # then edit it — see Configuration
docker compose -f compose.prod.yaml --env-file .env up -d
```

Or a bare container against a Postgres you already have:

```bash
docker run -p 8080:8080 \
  -e ARTEMIS_STUDIO_DB_URL=jdbc:postgresql://db:5432/artemis_studio \
  -e ARTEMIS_STUDIO_DB_USER=artemis_studio \
  -e ARTEMIS_STUDIO_DB_PASSWORD=... \
  -e ARTEMIS_STUDIO_SECRET_KEY="$(openssl rand -base64 32)" \
  sudoit1/artemis-studio:dev
```

## First login

The username is `admin`. The first run against an empty database generates the
password and prints it **once** to the container log:

```bash
docker compose -f compose.prod.yaml logs studio | grep -A4 'Created administrator'
```

You are forced to set a new password on first login. Lose that first password
before changing it and the only recovery is resetting the row directly in
Postgres — there is no password-reset flow yet.

## Register a cluster

Sign in, then **Clusters → Add**. You give Studio one seed node's management
endpoint and its credentials; the rest of the topology is discovered from the
broker itself. Credentials are encrypted at rest with `ARTEMIS_STUDIO_SECRET_KEY`.

If a capability is missing — the Core client is not reachable, or a management
operation is not exposed — Studio tells you which one and shows the exact
`broker.xml` snippet that enables it, rather than hiding the feature.

## Behind a reverse proxy

The live UI stream is Server-Sent Events at `GET /api/v1/stream`, and a proxy in
front of Studio **must not buffer it**:

- nginx — `proxy_buffering off;` on that path
- Apache — no output buffering on that path
- Traefik — works as-is

Without this the topology graph and the queue grid only update on the 5-second
poll, which looks like a product that is barely alive.

## Try it against throwaway brokers

If you want to see it working before pointing it at anything real, the
repository ships a dev stack that builds Studio from source and starts two real
live/backup Artemis pairs, then fills them with traffic that looks like a
working estate — including one address with no consumer so depth climbs, a real
dead-letter backlog, and one stopped node.

```bash
just dev-up                          # Postgres + an Artemis pair + Studio
ADMIN_PASSWORD=… just demo           # a second pair, plus realistic traffic
```

Nothing in that seed writes to `metric_sample` or `queue_snapshot` directly —
the traffic is produced and consumed through the broker's own CLI, so the charts
are measurements rather than fiction.

## Next

- [Configuration](/guide/configuration) — every variable it reads
- [SQL Console](/guide/sql-console) — asking a question that spans queues
- [MCP server](/guide/mcp) — the same capabilities for an assistant
