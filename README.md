<div align="center">

# Artemis Studio

**One console for every Apache ActiveMQ Artemis cluster you run.**

Live topology, every queue on every node in one table, safe message operations,
and SQL over your messages — from a single instance.

**English** · [简体中文](README.zh.md) · [فارسی](README.fa.md)

[![CI](https://github.com/sudoitir/artemis-studio/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/sudoitir/artemis-studio/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/sudoitir/artemis-studio?include_prereleases&sort=semver&label=release)](https://github.com/sudoitir/artemis-studio/releases)
[![Docker pulls](https://img.shields.io/docker/pulls/sudoit1/artemis-studio?logo=docker&label=pulls)](https://hub.docker.com/r/sudoit1/artemis-studio)
[![Licence](https://img.shields.io/badge/licence-Apache--2.0-blue)](LICENSE)
[![Last commit](https://img.shields.io/github/last-commit/sudoitir/artemis-studio)](https://github.com/sudoitir/artemis-studio/commits/main)
[![Stars](https://img.shields.io/github/stars/sudoitir/artemis-studio?style=flat)](https://github.com/sudoitir/artemis-studio/stargazers)

[**Docs**](https://sudoitir.github.io/artemis-studio/) ·
[Quickstart](https://sudoitir.github.io/artemis-studio/guide/quickstart) ·
[SQL Console](https://sudoitir.github.io/artemis-studio/guide/sql-console) ·
[MCP](https://sudoitir.github.io/artemis-studio/guide/mcp) ·
[Roadmap](#roadmap)

</div>

> [!WARNING]
> **Alpha.** Under active development, not yet feature-complete. Published images
> are pre-stable dev builds (`sudoit1/artemis-studio:dev` — no `:latest` yet).
> Expect breaking changes.

![Artemis Studio: topology, the cross-node queue grid, the dead-letter queue and the charts](docs/img/demo.gif)

## Why

The console that ships with Artemis manages **one broker at a time** and has no
idea a cluster exists. That is fine until your question spans nodes — and the
questions that matter always do: *which node is live*, *where is the depth*,
*where did that message go*.

Artemis Studio is the other thing: **one instance across many clusters**, with
the cluster as the unit of everything. It runs against your **existing** brokers
— no `broker.xml` rewrite beyond the management endpoints you almost certainly
already have — and it never starts a broker.

## Run it

```bash
git clone https://github.com/sudoitir/artemis-studio && cd artemis-studio
just up          # Studio + Postgres, secrets generated, pinned to the latest release
```

Then open <http://localhost:8080>. `just up` prints the generated `admin`
password once; you are forced to change it on first login.

<details>
<summary>Without <code>just</code>, or against your own Postgres</summary>

```bash
base=https://raw.githubusercontent.com/sudoitir/artemis-studio/main/deploy/compose
curl -sO "$base/compose.prod.yaml"
curl -s "$base/.env.example" -o .env   # then edit it
docker compose -f compose.prod.yaml --env-file .env up -d
```

```bash
docker run -p 8080:8080 \
  -e ARTEMIS_STUDIO_DB_URL=jdbc:postgresql://db:5432/artemis_studio \
  -e ARTEMIS_STUDIO_DB_USER=artemis_studio \
  -e ARTEMIS_STUDIO_DB_PASSWORD=... \
  -e ARTEMIS_STUDIO_SECRET_KEY="$(openssl rand -base64 32)" \
  sudoit1/artemis-studio:dev
```

Every variable, the reverse-proxy requirement for the SSE stream, and first-login
recovery are in the [configuration guide](https://sudoitir.github.io/artemis-studio/guide/configuration).

</details>

## SQL over your messages

Artemis cannot answer *"where did order 4471 go?"*. Its only server-side filter
is a JMS selector: message headers only — **never the body** — one queue, one
node at a time.

```sql
SELECT * FROM "ORDER.*"
WHERE body->>'orderId' = '4471'
LIMIT 50
```

![The SQL Console: a query across every queue in the cluster, its cost classified before it runs, then a live tail](docs/img/sql-console.gif)

A restricted, read-only dialect — `SELECT` only, parsed to an AST and validated
against a fixed column catalogue, so no mutation is expressible at all. Header
predicates become a JMS selector and cost nothing; body predicates are a scan.
**The plan strip says which, before the query runs**, and a query over the cost
ceiling is refused with the estimate rather than truncated — a truncated result
is indistinguishable from a complete one at a glance.

Add a live tail (a poll, never a consume) and an opt-in retention-bounded index
for messages that have already been consumed.
[More →](https://sudoitir.github.io/artemis-studio/guide/sql-console)

## What else it does

| Cluster topology | Cross-node queues |
|---|---|
| [![Live/backup topology with replication and a shared-NodeID axis](docs/img/topology.png)](docs/img/topology.png) | [![Every queue across every node in one virtualised grid](docs/img/queues.png)](docs/img/queues.png) |
| **Metrics and charts** | **Governance** |
| [![Depth, throughput and consumer charts from partitioned Postgres](docs/img/metrics.png)](docs/img/metrics.png) | [![Users, scoped grants, environments, API tokens and OIDC claim mapping](docs/img/governance.png)](docs/img/governance.png) |

- **Topology** — live/backup pairs and replication state, with the HA role polled
  from every node on every cycle. Never read from config; two live in a pair is a
  split-brain alert.
- **Cross-node resources** — queues, addresses, consumers, sessions, connections
  and producers in one virtualised table, attributed per node, over SSE.
- **Message operations** — browse, send, move, retry, expire, delete, purge, with
  `?dryRun=true` on every mutating call, a server-enforced bulk cap, and per-node
  outcomes.
- **Request-reply tracing** — requests correlated to replies across addresses and
  nodes, with latency and timeout statistics against declared expectations.
- **Governance** — authentication everywhere, a role/permission model scoped
  global → environment → cluster, API tokens, optional OIDC/SSO, and an audit
  event written in the same transaction as the command.
- **[MCP server](https://sudoitir.github.io/artemis-studio/guide/mcp)** — the same
  capabilities for an assistant, under the same grants and the same audit trail.

## Built on four rules

**Broker-friendly by construction** — batched reads (one Jolokia POST per node,
never one per queue), tiered polling, a per-node rate limiter. Studio must never
be the reason a broker falls over.
**Safe by default** — every destructive call dry-runs; purge and delete need the
resource's name typed.
**Never trust config for HA state** — who is live is a question for the live nodes.
**Honest capability gating** — an unavailable feature says so and shows the exact
`broker.xml` that enables it. Nothing is silently missing.

## Develop

Needs JDK 25, Node 22, Docker and [`just`](https://github.com/casey/just#packages).
A dev container is provided (`.devcontainer/`).

```bash
just dev-up          # Postgres + a real Artemis primary/backup pair + Studio, from source
just dev             # or: backend :8080 + Vite :5173, together, with live reload
just verify          # everything CI runs
```

`ADMIN_PASSWORD=… just demo` adds a second live/backup pair and fills all four
with realistic traffic — a consumer-less address whose depth climbs, a real
dead-letter backlog, one stopped node. The screenshots and the GIFs above are
recorded from it, by `just shots` and `just demo-gif`; nothing is staged.

Every feature goes through **OpenSpec** (`/opsx:propose` → `apply` → `archive`),
and significant decisions get an [**ADR**](docs/adr/). See
[`CLAUDE.md`](CLAUDE.md) and [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Stack

Java 25 · Spring Boot 4.1 · PostgreSQL with Liquibase · React 19 + Vite +
Mantine 9 · TanStack Router/Query/Table · React Flow · Jolokia HTTP first with
the Artemis Core client second · SSE · one container image.
[Architecture](https://sudoitir.github.io/artemis-studio/reference/architecture) ·
[all 61 decisions](https://sudoitir.github.io/artemis-studio/reference/adr/).

## Releases

Every push to `main` publishes a release. CalVer `YYYY.MM.PATCH`, three Docker
Hub tags — `2026.09.3` (immutable), `2026.09` (that month), `dev` (latest). No
`:latest` until the first stable release. Each release attaches the runnable jar
with a `.sha256`, and its notes are generated from the commit messages
([`changelog/`](changelog/)).

## Roadmap

Phases 0–8 are done: topology, cross-node views, message operations, the audit
trail, the Core client and request-reply tracing, metrics, alerting, governance,
the MCP server, and the SQL Console. What is left:

|  | |
|--|--|
| [ ] | Divert and bridge management |
| [ ] | Declared desired state and drift detection |
| [ ] | Multi-instance HA — a Postgres advisory lock per cluster |
| [ ] | Helm chart |
| [ ] | Message replay from a captured payload |
| [ ] | ArkMQ operator integration, JMX transport, saved views, scheduled reports |

## Licence

[Apache-2.0](LICENSE) — the same licence as Artemis itself.

Apache ActiveMQ and Apache ActiveMQ Artemis are trademarks of the Apache Software
Foundation. Artemis Studio is an independent project and is not produced by,
endorsed by, or affiliated with the Apache Software Foundation. References to
"Artemis" describe the broker this tool manages.

---

<div align="center">

If this saved you time, a ⭐ helps others find it.

</div>
