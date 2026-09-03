# ADR-0006: Own the metrics timeseries in PostgreSQL

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi

## Context

The product needs historical charts (queue depth, throughput, consumer counts,
request-reply latency). Options: store our own timeseries, or lean on a
Prometheus the operator may already run.

## Decision

**Store the timeseries in PostgreSQL and ship built-in charts.** No hard external
dependency for a core feature.

- `metric_sample` is `PARTITION BY RANGE (ts)` with a default partition. A Phase 6
  retention job creates daily partitions and drops expired ones.
- BRIN index on `ts`; btree on the `(cluster, subject_type, subject_name, metric,
  ts)` lookup path.
- `queue_snapshot` holds latest-state-per-queue (upsert), separate from the
  append-only history.
- Raw retention defaults to 7 days. Rollup tables are added **when a dashboard
  query gets slow**, not pre-emptively.
- **No TimescaleDB**: requiring an extension breaks "runs on any managed
  Postgres", a real adoption tax. Revisit only if plain partitioning proves
  insufficient at target scale (a few clusters, a few thousand queues).

Scale sanity check: ~3,000 queues × 8 metrics on a 15s tier-B scrape ≈ 5.8M
rows/day — fine for daily-partitioned Postgres on modest hardware.

## Consequences

- Single-binary story intact: `docker compose up` and you have charts.
- We own retention, rollup, and partition management code.
- Prometheus/Grafana users get no native integration in the MVP; a
  Prometheus-scrape option can be added later without disturbing this.

## Alternatives considered

- **Prometheus + embedded Grafana** — least code, but a hard dependency for a core
  feature and excludes operators without a Prometheus.
- **Hybrid (own short window, Prometheus for history)** — most flexible, most
  design surface; deferred.
