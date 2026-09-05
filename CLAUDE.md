# Artemis Studio — project instructions

Cluster-wide management and observability for Apache ActiveMQ Artemis. One instance,
many clusters: topology, cross-node resource views, safe message operations, and
first-class request-reply tracing. Open source, Apache-2.0.

The full design rationale is in `docs/architecture.md` and the ADRs. The remaining
work is the Roadmap in `README.md` — each item is a self-contained session.

## Stack (fixed — changing any of these needs an ADR)

- **Backend**: Java 25, Spring Boot 4.1.0, Maven. Package root
  `io.github.sudoitir.artemisstudio`. groupId `io.github.sudoitir`.
- **Persistence**: JPA (Hibernate) entities mapped to the Liquibase-owned schema,
  `spring.jpa.hibernate.ddl-auto=validate` (ADR-0011). **Lombok** for entity /
  component boilerplate and **MapStruct** for entity↔domain↔DTO mapping
  (ADR-0014) — needs the Lombok IDE plugin.
- **Database**: PostgreSQL. Schema via **Liquibase** — XML master changelog
  (`src/main/resources/db/changelog/db.changelog-master.xml`), one SQL changeset
  file per concern under `changes/`. Boot runs migrations on startup; the
  `liquibase-maven-plugin` (`just db-*`) is for humans. Postgres owns config,
  users, and audit; broker-derived tables (`queue_snapshot`, `metric_sample`) are
  a disposable cache.
- **Java formatting**: Palantir Java Format via Spotless, applied at
  `process-sources` (every `mvn compile`) and checked at `verify`. Never
  hand-fight it; run `just fmt`.
- **Frontend**: React 19 + Vite + TypeScript + **Mantine 9** (no Tailwind, no shadcn).
  TanStack Router/Query/Table, `@mantine/charts`, `@xyflow/react` for topology.
  `typescript` is pinned to `5.9.3` for tool compatibility; bump to 7.x once the
  ESLint/Vite/TanStack toolchain all declare support.
- **Broker transport**: Jolokia HTTP is primary; the Artemis Core client is the
  second channel (notifications, faithful message I/O), added in Phase 4. No JMX.
- **Realtime**: SSE only. `GET /api/v1/stream`. Commands are ordinary POSTs.
- **Packaging**: one container image, `compose.yaml` first. Helm in v1.0.

## Non-negotiables

1. **Broker-friendly by construction.** Batched Jolokia reads (one POST per node,
   not per queue), tiered polling, a per-node rate limiter. Studio must never be
   the reason a broker falls over.
2. **Safe by default.** Every destructive operation takes `?dryRun=true` and returns
   the affected count without acting. Purge/delete need typed confirmation in the UI.
3. **Audit everything.** Every mutating call writes an `audit_event` in the same
   transaction as the command, before the broker call, updated with the outcome.
4. **Never trust config for HA state.** Poll `Active` on every node to learn who is
   live. Two live in a pair = split-brain = critical alert.
5. **Honest capability gating.** When a feature is unavailable because the connection
   lacks a capability, the UI says so and shows the exact `broker.xml` snippet.
   No silently missing buttons.
6. **Tokens, three layers.** `web/src/theme.ts` (primitive) → `web/src/theme.css`
   semantic vars (`--as-*`) → components. No raw hex or colour literals in components.
7. **Schema conventions.** Columns ordered to minimise Postgres row padding:
   8-byte-aligned types (timestamptz, bigint, double precision) first, then 4-byte
   (integer, text, jsonb, bytea, inet), then uuid and boolean last — the PK is not
   first, on purpose. High-churn tables carry per-table `autovacuum` / `fillfactor`
   storage parameters in the same changeset. Server-level tuning lives in
   `deploy/postgres/postgresql.tuning.conf` (and inline in the compose files).
   Never edit a released changeset — add a new one.
8. **Logical CSS properties** (`inline-start`, `block-end`), never `left`/`right`.
9. **State has one owner.** Server state via TanStack Query; navigable state in the
   URL; local state stays local. No global store for what a URL can hold.
10. **The product name lives in two files.** `Branding.java` and `web/src/branding.ts`.
   Never hard-code "Artemis Studio" anywhere else. See ADR-0001 for why.

## Process

- **ADRs are binding.** `docs/adr/`, English, Nygard style (`docs/adr/000-template.md`).
  Adding a technology, changing a pattern, or departing from a convention needs a new
  sequential ADR before or with the change. Never edit an accepted ADR's decision —
  supersede it.
- **Library/API facts come from `ctx7`, not memory.** Any question about a library,
  framework, SDK, or CLI — including ones you think you know — goes through the `ctx7`
  CLI (`npx ctx7@latest library "<name>" "<query>"` then `docs "<id>" "<query>"`).
  Max 3 calls per question. Training data on versions and signatures is stale.
- **Read before you write.** Open the token file and the nearest existing screen
  before adding UI. Grep for an existing helper before writing one.
- **Smallest correct change.** No drive-by refactors. Fix the thing, file the rest.
- **Don't fake the receipt.** If you didn't run it, say so. If a test fails, show the
  output. A confident wrong answer costs more than an honest "let me check".

## Commands

```bash
just                 # menu of all tasks, grouped
just up / just down  # Studio + Postgres from the published image (writes .env on first run)
just dev-up / dev-down  # full dev stack: postgres + artemis primary/backup + studio, built locally
just dev             # backend :8080 + vite :5173 (proxied), together
just verify          # everything CI runs: verify-api + verify-web
just fmt             # Palantir (Spotless) + eslint --fix
just db-status / db-sql / db-rollback [n] / db-shell

./mvnw verify                  # backend: format-check, Liquibase vs Testcontainers PG, tests
./mvnw -Pfrontend package      # full jar with the SPA baked in
```

`./mvnw verify` needs Docker (Testcontainers). The frontend build is behind the
`frontend` Maven profile so day-to-day `./mvnw test` stays fast. Compose files
live in `deploy/compose/` (`compose.dev.yaml`, `compose.prod.yaml`).

Every push to `main` cuts a CalVer release to Docker Hub (image, git tag, GitHub
pre-release). See `.claude/rules/10-release.md` for the versioning and
`CHANGELOG.md` conventions — user-visible changes add a `## [Unreleased]` entry.

## Trademark

"Artemis" is an Apache Software Foundation mark. This project uses it as a product
name by a deliberate, recorded decision (ADR-0001) with a cheap rename path.
Keep the disclaimer (`NOTICE`, README, About dialog) intact. References to the
broker are nominative use.
