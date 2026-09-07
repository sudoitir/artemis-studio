# Artemis Studio

[![CI](https://github.com/sudoitir/artemis-studio/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/sudoitir/artemis-studio/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/sudoitir/artemis-studio?include_prereleases&sort=semver&label=release)](https://github.com/sudoitir/artemis-studio/releases)
[![Docker image](https://img.shields.io/docker/v/sudoit1/artemis-studio?sort=semver&logo=docker&label=docker%20hub)](https://hub.docker.com/r/sudoit1/artemis-studio/tags)
[![Docker pulls](https://img.shields.io/docker/pulls/sudoit1/artemis-studio?logo=docker&label=pulls)](https://hub.docker.com/r/sudoit1/artemis-studio)
[![Licence](https://img.shields.io/badge/licence-Apache--2.0-blue)](LICENSE)

> **⚠️ Alpha.** Under active development, not yet feature-complete. Published
> images are **pre-stable dev builds** (Docker Hub tag `:dev`, no `:latest` yet).
> Expect breaking changes. See [Releases](#releases).

**Cluster-wide management and observability for Apache ActiveMQ Artemis.**

The bundled Hawtio console manages one broker at a time and has no idea a cluster
exists. Artemis Studio is the other thing: one instance across many clusters —
live/backup topology, cross-node queues and addresses in a single table, safe
message operations, and first-class request-reply tracing.

It works against your **existing** brokers. No `broker.xml` rewrite beyond
enabling the management endpoints you almost certainly already run.

## Screenshots

| Cluster topology | Cross-node queues |
|---|---|
| [![Live/backup topology with replication and shared-NodeID axis](docs/img/topology.png)](docs/img/topology.png) | [![Every queue across every node in one virtualized grid](docs/img/queues.png)](docs/img/queues.png) |
| **Metrics and charts** | **Governance (RBAC, environments, tokens, SSO)** |
| [![Depth, throughput and consumer charts from partitioned Postgres](docs/img/metrics.png)](docs/img/metrics.png) | [![Users, scoped grants, environments, API tokens and OIDC claim mapping](docs/img/governance.png)](docs/img/governance.png) |

---

## Status

**Alpha.** Phases 0–8 are done: topology, cross-node queue/address/consumer views
over live SSE, message operations with dry-run and a server-enforced bulk cap,
a full audit trail, the Core client and request-reply tracing, metrics and
charts, alerting, and — as of Phase 8 — governance: every endpoint requires
authentication (session cookie for the browser, API tokens for automation,
optional OIDC/SSO), a dynamic role/permission model scoped to global /
environment / cluster, and per-environment cluster grouping. An MCP server
(`/mcp`) exposes the same capabilities to an assistant under the same grants and
the same audit trail — see [MCP](#mcp). v1.0 (hardening and reach) is next — see
the [Roadmap](#roadmap).

## Run it

Runs Artemis Studio and its Postgres from the published image. Your Artemis
clusters already exist and are registered through the UI — nothing here starts a
broker. Images are **pre-stable dev builds**; see [Releases](#releases).

From a clone, with [`just`](https://github.com/casey/just#packages):

```bash
just up          # writes deploy/compose/.env with fresh secrets, pinned to the latest release
open http://localhost:8080
```

`just up` runs `just setup` first: on a clean checkout it generates
`deploy/compose/.env` with a random `SECRET_KEY` / `DB_PASSWORD` and pins
`STUDIO_IMAGE` to the newest published [CalVer tag](#releases) (or `:dev` if no
release exists yet). Run `just setup` again to move the pin forward.

Without `just` — grab the compose file and provide the environment yourself:

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
| `ARTEMIS_STUDIO_SECRET_KEY` | yes | Encrypts stored broker credentials (ADR-0009). Base64 of **exactly 32 bytes** or the app won't start: `openssl rand -base64 32` |
| `ARTEMIS_STUDIO_CONFIG_ENCRYPT_KEY` | no | Decrypts `{cipher}` values stored in `studio_config_property` (ADR-0047). A **different** key from `ARTEMIS_STUDIO_SECRET_KEY` — do not reuse it |
| `JAVA_OPTS` | no | Defaults to `-XX:MaxRAMPercentage=75` |

**First login.** Username `admin`. The first run against an empty database
generates the password and prints it **once** to the container log:

```bash
docker compose -f compose.prod.yaml logs studio | grep -A4 'Created administrator'
```

You'll be forced to set a new password on first login (`must_change_password`,
ADR-0037). Lose that first password before changing it and the only recovery is
resetting the row directly in Postgres — there is no password-reset flow yet.

**Reverse proxy.** The live UI stream is SSE at `GET /api/v1/stream`; a proxy in
front of Studio **must not buffer it** (nginx `proxy_buffering off;`, Apache no
output buffering on that path; Traefik works as-is). Without this the topology
graph and queue grid only update on the 5-second poll.

## MCP

Studio speaks the [Model Context Protocol](https://modelcontextprotocol.io), so
an assistant can answer "why is `ORDERS.DLQ` backed up" against your real
clusters instead of guessing. The surface is about a dozen intent-shaped tools —
`cluster_health`, `diagnose_queue`, `queue_action` and so on — not a mirror of
the REST API ([ADR-0045](docs/adr/0045-mcp-server-is-a-capability-surface.md)).

**Get a key.** Sign in → avatar menu → **Account** → **API keys** → **New key** →
choose the scope and permissions it should carry → copy the value. It is shown
once.

**Connect.** The endpoint is `POST /mcp` on the same origin as the UI, with the
key as a bearer token:

```json
{
  "mcpServers": {
    "artemis-studio": {
      "type": "http",
      "url": "https://studio.example.com/mcp",
      "headers": { "Authorization": "Bearer as_..." }
    }
  }
}
```

Smoke-test it without a client:

```bash
curl -s https://studio.example.com/mcp \
  -H "Authorization: Bearer as_..." \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

**What is there.** The ADR is authoritative; this is the shape of it.

| Kind | Name | For |
|---|---|---|
| Tool | `cluster_health` | HA role per node, split-brain, replication lag, firing alerts |
| Tool | `list_resources` | queues, addresses, consumers, sessions, connections, producers |
| Tool | `diagnose_queue` | one queue end to end: depth, trend, consumers, DLQ, events |
| Tool | `metric_series` | a bucketed timeseries for one metric |
| Tool | `config_diff` | classified configuration differences between two nodes |
| Tool | `browse_messages` / `message_body` | headers, then one body by id |
| Tool | `trace_request_reply` | flows, latency/timeout stats, configured expectations |
| Tool | `activity_log` | broker events, or Studio's audit trail |
| Tool | `queue_action` | move / retry / delete / expire / purge |
| Tool | `send_message` | enqueue one message |
| Tool | `alert_rule` / `studio_setting` | alert rules; operational settings |
| Resource | `studio://clusters`, `studio://permissions` | what this key can see and do |
| Resource | `cluster://{id}/topology`, `cluster://{id}/capabilities` | nodes; what the connection supports, with the `broker.xml` to enable what it does not |
| Prompt | `triage_cluster`, `investigate_queue`, `before_you_purge`, `tune_scrape_load` | runbooks |

**The safety contract**, plainly:

- A key never exceeds its owner. Grants are intersected with the owner's *live*
  grants on every call, so narrowing a person narrows their keys at once
  ([ADR-0046](docs/adr/0046-mcp-authenticates-with-existing-api-tokens.md)).
- Mutations dry-run by default. A real destructive run additionally requires
  `confirm` to equal the queue's own name — separate from the bulk-cap
  `override`, which is a different question and is never satisfied by `confirm`.
- Everything is audited under the owner with the key's name attached
  (`ada [token: laptop-agent]`), dry runs included.
- A cluster the key holds no grant on comes back as "no such cluster, or this key
  has no grant on it" — naming no permission and confirming no id.

## Develop

The dev stack adds a real Artemis primary/backup pair and builds Studio locally.
Needs JDK 25, Node 22, Docker, and [`just`](https://github.com/casey/just#packages).
A dev container is provided (`.devcontainer/`, Ubuntu 26.04 LTS).

```bash
just dev-up      # Postgres + Artemis primary/backup + Studio (built from source)
just dev         # or: backend :8080 + Vite :5173, together, with live reload
```

Without `just`: `docker compose -f deploy/compose/compose.dev.yaml up --build -d`.
`just` on its own lists every task, grouped.

## Build and test

```bash
just verify          # backend (Liquibase vs Testcontainers Postgres, tests) + frontend build + lint
./mvnw -Pfrontend package   # single jar with the SPA baked in
```

## Stack

| Layer | Choice | Why |
|---|---|---|
| Backend | Java 25, Spring Boot 4.1.0, Maven | — |
| Database | PostgreSQL, Liquibase migrations | [ADR-0008](docs/adr/0008-schema-migrations-liquibase.md) |
| Frontend | React 19 + Vite + Mantine 9, TanStack Router/Query/Table, React Flow | [ADR-0005](docs/adr/0005-frontend-stack-mantine-over-shadcn-and-mui.md) |
| Broker transport | Jolokia HTTP first, Artemis Core client second, capability-gated | [ADR-0002](docs/adr/0002-broker-transport-and-capability-model.md) |
| Realtime | SSE | [ADR-0003](docs/adr/0003-realtime-via-sse.md) |
| Packaging | one container image, Docker Compose first | [ADR-0007](docs/adr/0007-packaging-single-image-compose-first.md) |
| MCP | Spring AI 2.0.1, stateless Streamable HTTP at `/mcp` | [ADR-0045](docs/adr/0045-mcp-server-is-a-capability-surface.md) |

Architecture: [`docs/architecture.md`](docs/architecture.md). All decisions:
[`docs/adr/`](docs/adr/).

## Releases

Every push to `main` publishes a release — [ADR-0042](docs/adr/0042-calver-releases-on-docker-hub.md).
Versioning is CalVer `YYYY.MM.PATCH` (`PATCH` resets each month), and the
[Docker Hub image](https://hub.docker.com/r/sudoit1/artemis-studio) carries three tags:

| Tag | Meaning |
|---|---|
| `2026.09.3` | immutable — a specific build |
| `2026.09` | moving — latest build of that month |
| `dev` | moving — latest build overall |

No `:latest` is published until the first stable release. Each release also
attaches the runnable jar (with a `.sha256`) to a GitHub pre-release. Changes are
recorded in [`changelog/`](changelog/) — one file per version, generated from the
commit messages in that release ([ADR-0051](docs/adr/0051-changelog-generated-from-commits.md)).
The conventions live in [`.claude/rules/05-commits.md`](.claude/rules/05-commits.md)
and [`.claude/rules/10-release.md`](.claude/rules/10-release.md).

The Docker Hub page's own description is [`docs/dockerhub.md`](docs/dockerhub.md),
pushed by the same release job — this README is not what appears there.

## How work happens

Every feature goes through **OpenSpec** (`/opsx:propose` → `apply` → `archive`).
Significant decisions get an **ADR**. Library facts come from `ctx7`, not memory.
See [`CLAUDE.md`](CLAUDE.md) and [`.claude/rules/`](.claude/rules/).

---

## Roadmap

Phases 0–8 are **done** — everything in [Status](#status), from topology and
cross-node views through request-reply tracing, metrics, alerting, and
governance. What's left, roughly in order. Every feature phase goes through
OpenSpec (`/opsx:propose` → `apply` → `archive`); significant decisions get an
[ADR](docs/adr/).

### Broker management

|  | Task |
|--|------|
| [x] | Queue and address lifecycle — create, destroy, reconfigure, pause and resume across a cluster ([ADR-0049](docs/adr/0049-cluster-wide-topology-mutation.md)) |
| [x] | Connection and consumer control — close a connection, a session, or an address's consumers ([ADR-0057](docs/adr/0057-connection-identifiers-are-node-local.md)) |
| [ ] | Divert and bridge management |
| [ ] | Declared desired state and drift detection |

### v1.0 · Hardening and reach

|  | Task |
|--|------|
| [ ] | Multi-instance HA — Postgres advisory lock per cluster |
| [ ] | Helm chart |
| [ ] | Docs site |
| [ ] | Message replay from a captured payload |
| [ ] | Metric rollup tables — only when a query is measurably slow |

### Beyond

|  | Task |
|--|------|
| [ ] | ArkMQ operator integration (read broker CRs to auto-register) |
| [ ] | JMX transport |
| [ ] | Saved / shareable views |
| [ ] | Scheduled reports |
| [ ] | Prometheus scrape ingestion option |


---

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Issues and PRs welcome.

## Licence

[Apache-2.0](LICENSE). Same licence as Artemis itself.

Apache ActiveMQ and Apache ActiveMQ Artemis are trademarks of the Apache Software
Foundation. Artemis Studio is an independent project and is not produced by,
endorsed by, or affiliated with the Apache Software Foundation. References to
"Artemis" describe the broker this tool manages.
