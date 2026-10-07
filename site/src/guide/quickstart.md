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

The built-in **ADMIN** role requires two-step verification, so right after you choose a
password Studio asks you to set up an authenticator app (scan the QR code, then confirm
with a code) or a passkey, and shows ten single-use recovery codes once. Keep them: they
are the way back in when the device is lost. Every later sign-in asks for the password and
then a code.

A script or `curl` signs in the same way. `POST /api/v1/auth/login` answers
`{"status": "SECOND_FACTOR_REQUIRED", …}` for an account that has a factor, and
`POST /api/v1/auth/second-factor` takes `{"totpCode": "123456"}`. An account with none yet
sets one up with `POST /api/v1/auth/mfa/totp` (it returns the `secret` and an
`otpauthUri`) and confirms it with `POST /api/v1/auth/mfa/totp/confirm` and
`{"code": "123456"}`, which returns the recovery codes. API keys are not asked for a code.

## Register a cluster

Sign in, then **Clusters → Add**. One broker's management URL is enough: give Studio that URL and the
management account (and a separate Core account, if the brokers use one), and it finds the rest of the
cluster from the broker itself. Credentials are encrypted at rest with `ARTEMIS_STUDIO_SECRET_KEY`.

Artemis advertises only its Core connectors, never where a broker's management endpoint is. Studio
therefore keeps a **management URL pattern** for the cluster (`http://{host}:8161/console/jolokia`: your
URL with the host left free, editable under *Advanced*), puts each other node's host into it, and asks
that address which broker answers. The address is kept only when the answering broker reports the node's
NodeID; otherwise the node is listed as found, with the reason, and you can give it a URL by hand. If your
host name resolves to several addresses, Studio tries each one as a seed. Choose an environment on the same form.

**Check connection** lists every node it found, with its role, NodeID, version, management URL (and whether
it came from your seed, from the pattern or was set by hand), and what the management account and the Core
account each did there: accepted, rejected, unreachable or not tried. A refused Core account does not hide
an accepted management account. When the brokers' running configuration can be read, the check also offers
to **adopt** it as the cluster's first declared revision: the switch is on when the nodes agree, and off,
with the differences listed, when they do not. Nothing is written to a broker either way.

Brokers are registered once. Studio knows a broker by the NodeID it reports, not by the URL you
typed, so the same broker reached by its IP address, another port or with a trailing slash is still the
same broker. If any broker the check reaches or discovers already belongs to a registered cluster,
**Check connection** says which cluster, links to it, and **Register cluster** stays disabled. That holds
when only some of the brokers overlap, and when two people register the same brokers at the same moment:
one cluster is created and the other registration is refused the same way. A broker cloned or restored from
another broker's data directory carries the same NodeID, so Studio takes it for that broker; give it a
fresh journal and it gets its own.

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
ADMIN_PASSWORD=… NEW_ADMIN_PASSWORD=… just demo   # a second pair, plus realistic traffic
```

The password `just dev-up` prints is one-time: Studio refuses everything else until it
is changed. `NEW_ADMIN_PASSWORD` is the one you choose; the seed changes it for you on
the first run. It also sets up an authenticator app for `admin`, which its role requires,
and prints `ADMIN_TOTP_SECRET`. Later runs, and `just shots` and `just demo-gif`, need
`ADMIN_PASSWORD` set to the new password and `ADMIN_TOTP_SECRET` to that secret.

Nothing in that seed writes to `metric_sample` or `queue_snapshot` directly —
the traffic is produced and consumed through the broker's own CLI, so the charts
are measurements rather than fiction.

## Next

- [Configuration](/guide/configuration) — every variable it reads
- [Broker configuration](/guide/broker-configuration) — declaring what a cluster
  should run, and closing the capability gaps a first launch reports
- [SQL Console](/guide/sql-console) — asking a question that spans queues
- [MCP server](/guide/mcp) — the same capabilities for an assistant
