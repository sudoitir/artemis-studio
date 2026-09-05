# Artemis Studio — project context for OpenSpec

## What this is

Cluster-wide management and observability for Apache ActiveMQ Artemis. One
instance manages many clusters: live/backup topology, cross-node resource views,
safe message operations, and first-class request-reply tracing. Works against
existing brokers via their standard management endpoints — no `broker.xml` rewrite
beyond enabling those endpoints.

## Stack

- Backend: Java 25, Spring Boot 4.1.0, Maven.
- Database: PostgreSQL, schema via Liquibase (XML master + SQL changesets).
- Frontend: React 19 + Vite + TypeScript + Mantine 9 (TanStack Router/Query/Table,
  `@mantine/charts`, `@xyflow/react`).
- Broker transport: Jolokia HTTP primary; Artemis Core client second (Phase 4+).
- Realtime: SSE.
- Packaging: single container image, Docker Compose (`deploy/compose/`).

## Conventions

- ADRs in `docs/adr/` are binding. Reference the ones a change depends on.
- `ctx7` for all library/API facts, never memory.
- Safe-by-default: destructive ops take `?dryRun=true`; audit every mutation.
- Broker-friendly: batched management calls, tiered scraping, per-node rate limit.
- Schema: columns ordered by alignment to cut row padding; per-table autovacuum
  on high-churn tables; never edit a released changeset.
- Frontend tokens: primitive (`theme.ts`) → semantic (`theme.css` `--as-*`) →
  component. Logical CSS properties only.

## Roadmap

The Roadmap in `README.md`. Current phase: 8 complete
(governance — session-cookie auth for the browser and API tokens for
automation, both landing on one `StudioPrincipal` shape; a fully dynamic
`resource:verb` permission model resolved once per request via a
cluster→environment→global scope walk; environments as a first-class
grouping; optional OIDC/SSO with JIT provisioning and claim→role mapping
re-applied every login; a reworked audit actor carrying real identity and
token attribution — ADR-0037 through ADR-0041, superseding ADR-0023);
v1.0 (hardening and reach) next.

## How to work a change

`/opsx:propose` → `/opsx:apply` → `/opsx:archive`. One change in flight at a time.
`openspec/specs/` is the source of truth for current behaviour.
