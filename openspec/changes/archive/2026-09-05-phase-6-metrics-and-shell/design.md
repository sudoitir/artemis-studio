## Context

See `proposal.md` — Why. Relevant current state: `MetricSampleWriter` already appends 4
samples/queue on tier B/C; `metric_sample` has no JPA entity, no reader, and a single
default partition (changeset `005-broker-cache.sql`). `topology/layout.ts`'s `layout()` is
a pure function of `(TopologyView, HealthView)`, already reused nowhere but
`TopologyGraph`. `AppShell`'s `collapsed` prop removes the navbar's width entirely rather
than shrinking it to an icon rail (confirmed against current Mantine docs via ctx7).

## Goals / Non-Goals

**Goals:**
- Bucketed metric reads that never return more than ~500 points and are correct across a
  broker restart (no negative counter-derived rates).
- A registration flow whose preview uses the exact same topology renderer as a live
  cluster.
- A sidebar that holds both cluster switching and per-cluster view navigation, collapsible
  to an icon rail, state persisted per browser.
- Zero new npm/Maven dependencies.

**Non-Goals:**
- Rollup/downsample tables (ADR-0006 gates these on a measured slow query — none exists).
- Persisted request-reply latency history (ADR-0032 stands; the RR chart is a live window).
- Writing `BROKER`/`ADDRESS` `subject_type` rows — cluster totals are computed as a SQL
  `sum` over `QUEUE` rows at query time.
- Mobile/responsive breakpoints for the shell.
- Alert thresholds on charts (Phase 7).

## Decisions

**1. Bucketing happens in Postgres via `date_bin`, not in the JVM or the browser.**
Alternative considered: fetch raw rows and downsample in Java. Rejected — it multiplies
network and heap cost for exactly the volume `ix_metric_sample_lookup` and the `ts` BRIN
index already index for, and duplicates work every request. `date_bin(:step, ts,
TIMESTAMPTZ '2000-01-01')` with a fixed epoch keeps bucket boundaries stable across
requests with different `from`/`to`.

**2. Gauge vs. counter is a fixed lookup by metric name, not a stored flag.**
`messageCount`/`consumerCount` are point-in-time gauges; `messagesAdded`/`messagesAcked`
are broker-lifetime monotonic counters. The distinction is intrinsic to what
`MetricSampleWriter` writes today, not configurable — a compile-time map in
`MetricQueryService` (`GAUGE_METRICS` / `RATE_METRICS`) is sufficient. Adding a `kind`
column to `metric_sample` was considered and rejected: it's per-metric-name, not
per-row, so a lookup table (in code, not SQL) is simpler and needs no migration.

**3. Rate is `GREATEST(max(value) - min(value), 0) / bucket_seconds`, computed per
subject before summing.** Alternative: `max - min` across all matching rows in the bucket
regardless of subject. Rejected — collapsing multiple queues' independent counters into
one `max`/`min` pair produces a meaningless number (e.g., queue A rising while queue B's
higher absolute value dominates the `max`). Per-subject delta, then sum, is the only
version where "total throughput" means what it says. The `GREATEST(...,0)` clamp is the
broker-restart guard — a counter reset must read as zero throughput for that bucket, not
a large negative spike.

**4. Partition maintenance drops via a plain `DETACH` then `DROP`, not `DELETE`.**
A `DROP` on a still-attached partition takes `ACCESS EXCLUSIVE` on the parent for the
duration of a full catalog+data drop. `DETACH` first, then `DROP` the now-standalone
table, splits that into two cheaper steps. `DETACH ... CONCURRENTLY` was the original
choice (avoids even the brief `ACCESS EXCLUSIVE` a synchronous detach takes) but
Postgres refuses `CONCURRENTLY` outright on a partitioned table that carries a `DEFAULT`
partition — confirmed against a real Postgres 17 in `MetricPartitionMaintainerTest` — and
Decision 5 keeps the default partition permanently. So the detach is synchronous. This is
still safe: `DETACH` is a catalog-only operation (no row scan), so the `ACCESS EXCLUSIVE`
hold is on the order of milliseconds regardless of how much data the partition holds —
unlike a `DROP` on an attached partition, whose lock has to cover the drop itself.

**5. The default partition is kept permanently, not migrated away.** Rows written before
the first maintainer run (or during any gap) land there. `MetricSampleReaper`'s existing
bounded `DELETE` is narrowed to target `metric_sample_default` only — a second, coarser
disposal path for exactly the rows partition-based dropping cannot reach by date range.

**6. `TopologyCanvas` is extracted from `TopologyGraph` as a pure presentational
component.** `TopologyGraph` currently owns both the data hooks (`useTopology`,
`useHealth`) and the `ReactFlow` rendering. Splitting the render half out
(`TopologyCanvas({ model, interactive })`, taking a `TopologyLayout` built by the already-
pure `layout()`) is the one piece of reuse that makes the registration preview and example
cards cheap: they call `layout()` with a hand-built or server-returned `TopologyView` and
get the identical visual grammar the rest of the app uses, with no fork.

**7. Sidebar collapse is an animated `navbar.width` change (264↔64), not `AppShell`'s
`collapsed` prop.** `collapsed.desktop` sets the navbar to width 0 and removes the `Main`
offset entirely — verified against current Mantine docs. An icon rail needs the navbar
*present* at a smaller width, so `collapsed` stays `false` and `navbar.width` changes
between two constants under `AppShell`'s own `transitionDuration`, which already owns
`--app-shell-navbar-width` and transitions the `Main` offset in lockstep. A hand-rolled
flex shell was considered and rejected — it would re-derive offset math, sticky header
behavior, and z-index stacking `AppShell` already gets right.

**8. Collapse state persists via `@mantine/hooks`' `useLocalStorage`, not a global
store.** One boolean, one browser tab's worth of state, no cross-component sharing need
beyond `RootLayout`. `getInitialValueInEffect: false` reads synchronously so there's no
expand→collapse flash on first paint. A global store (zustand/jotai) was rejected per
non-negotiable #9 — no global store for what `localStorage` already holds.

**9. Registration keeps its hand-rolled form state.** `@mantine/form` is not a project
dependency; the form's both-or-neither credential validation is bespoke and already
correct. Introducing a form library to keep existing behavior identical is a net
complexity increase for zero behavior change.

## Risks / Trade-offs

- **[Risk]** Widening the requested bucket step silently could mislead an operator reading
  a chart's x-axis. → **Mitigation**: the response always echoes the step actually used
  and a `truncated`/adjusted flag; the frontend renders the echoed step, never the
  requested one, and shows a caption when adjusted.
- **[Risk]** A synchronous `DETACH` still takes `ACCESS EXCLUSIVE` on `metric_sample`
  for its (brief, catalog-only) duration; a pathological pile-up of many expired
  partitions in one maintenance run would serialize those brief locks back-to-back. →
  **Mitigation**: the create-ahead/retention-drop cadence is daily, so at most one
  partition is ever expired per run in normal operation; verified in
  `MetricPartitionMaintainerTest` that a concurrent insert still succeeds immediately
  after a drop.
- **[Risk]** The registration preview's synthetic all-`UNKNOWN` health could visually
  imply something is wrong (yellow/red) rather than merely unpolled. → **Mitigation**:
  `layout()` already maps `UNKNOWN` to `--as-node-unmanaged`, a neutral dimmed token, not a
  warning color — verified against `theme.css`. No renderer change needed.
- **[Trade-off]** Deleting the horizontal view-strip navigation outright (no dual nav
  during a transition period) means every internal link/bookmark pattern that assumed the
  strip's presence changes at once. Accepted per the project's no-back-compat convention
  and because the strip already overflows at 12 items — keeping it isn't a safety net,
  it's a second copy of the same defect.

## Migration Plan

1. Add changeset `012-metric-partitions.sql` (never edit `005`). Boot runs it via
   Liquibase on next deploy; the maintainer's first scheduled run creates today+3 days'
   partitions.
2. Ship the read endpoint and UI in the same deploy — there is no intermediate state
   where partitions exist but nothing reads them, since the write path is unchanged.
3. `RegisterPreview` DTO change and sidebar navigation change ship as a single frontend
   build (`gen:api` regenerates `schema.d.ts` from the updated `openapi.json`); there is
   no gradual rollout since this is a single-instance, single-image product with no
   independent frontend/backend versioning (ADR-0007).
4. Rollback: revert the deploy. The new changeset is additive (new partitions + a
   maintainer job) and does not alter `metric_sample`'s existing columns or the default
   partition, so a rollback loses only the daily-partition optimization, not data.
