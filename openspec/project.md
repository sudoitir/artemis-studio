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

`docs/roadmap.md` and the TODO list in `README.md`. Current phase: 4 complete
(Core protocol client subscribed to `activemq.notifications` on every live
node, failover-following via `CoreSubscriptionManager`; `broker_event`
persistence with a buffered writer and retention reaper; a data-bearing SSE
`events` topic with `Last-Event-ID` replay and coalesced derived signals;
faithful Core-backed message browse/send with Jolokia fallback past the deep-page
cap and for all mutations; honest `NOTIFICATIONS` capability off a cached
subscription verdict); Phase 5 (request-reply tracing) next.

## How to work a change

`/opsx:propose` → `/opsx:apply` → `/opsx:archive`. One change in flight at a time.
`openspec/specs/` is the source of truth for current behaviour.
