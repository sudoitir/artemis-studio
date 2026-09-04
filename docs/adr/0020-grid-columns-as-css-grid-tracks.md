# ADR-0020: The data grid lays out on CSS grid tracks, not on `<table>`

- **Status**: accepted
- **Date**: 2026-09-04
- **Deciders**: Mahdi Amirabdollahi

## Context

`VirtualTable` is the one grid behind every cross-node view (queues, addresses,
consumers, sessions, connections, producers). It renders a Mantine `Table` and
virtualizes rows with `@tanstack/react-virtual`, which takes each row out of
flow and places it by `translateY`. A row out of flow is no longer part of the
table's layout, so it was given `display: table; table-layout: fixed` and a copy
of the header's column widths.

That produced two structural defects that survived a first round of fixes:

- **Two layout contexts, kept in step by hand.** The header ran the fixed-table
  algorithm once and every row ran it again, agreeing only for as long as their
  inputs stayed byte-identical. Any divergence — a different padding on `th`, a
  scrollbar, a border — shifted the columns out from under their headers.
- **Slack went to the wrong columns.** The fixed-table algorithm divides space
  left over above the declared widths *equally*. Measured at 1600px: `Depth`,
  a single digit, was 137px wide while `Address` ellipsized at 200px. Every view
  spent its width on the columns that needed none of it.

A third defect came from the same shape: the virtualizer stepped `ROW_HEIGHT`
(34px) while CSS gave rows 36.7px, so consecutive rows overlapped by 2.7px and
covered each other's separator.

## Decision

We will lay the grid out as **CSS grid over one track list**.

- **One `grid-template-columns`, declared once** as a `--as-cols` custom
  property on the grid element and consumed by the header row and every body
  row. Both are grid containers over the same tracks, so alignment is
  structural: there is no second layout pass that could drift.
- **A column is fixed or free.** `GridColumn.width` means a fixed px track, for
  values of known shape (a count, a routing type, a yes/no). Omitting it means
  `minmax(180px, 1fr)`: free-text columns — address, queue name, client id —
  split every pixel the fixed columns do not need.
- **The floors add up to the grid's `min-inline-size`.** Below their sum the
  grid stops shrinking and its own container scrolls; the page never does. That
  same width is what out-of-flow rows resolve `inset-inline: 0` against, so
  header and rows are laid out over an identical box at every viewport width.
- **Row height has one definition.** The `ROW_HEIGHT` constant feeds the
  virtualizer's `estimateSize` and, as `--as-row-h`, the row's `block-size` and
  the cell's `line-height`. The step and the box cannot disagree.
- **Semantics come from ARIA**, not from element names: `role="grid"` / `row` /
  `columnheader` / `gridcell`, with `aria-rowcount` and `aria-rowindex` carrying
  the true row numbers past the virtualization window.

Mantine `Table` remains the right component for a small, non-virtualized table
rendered in flow — the per-node breakdown in `QueueDetailDrawer`, wrapped in
`Table.ScrollContainer`. This ADR governs the virtualized grid only.

## Consequences

- The alignment defect class is gone by construction rather than by agreement,
  and column geometry is one string a reader can inspect in the DOM.
- Width tuning per view is now a real lever: declare a width to pin a column,
  omit it to let the column breathe. No view-local CSS.
- We give up native table semantics and take on the ARIA grid contract; a
  screen-reader regression is now possible in a way it was not before, and
  `aria-rowindex` must stay correct as virtualization changes.
- Mantine's `Table` styling no longer applies to the grid, so its look —
  header background, separators, hover, tabular numerals — is spelled out in
  `VirtualTable.module.css` against the same `--as-*` tokens (non-negotiable #6).
  Mantine theme changes will not reach it automatically.

## Alternatives considered

- **Keep `<table>`, give free columns `width: auto`.** The fixed-table algorithm
  does divide leftover space among auto columns, which would have fixed the
  slack defect in three lines. It leaves the two-layout-context fragility
  untouched, and that is what had already broken twice.
- **Drop virtualization and let one table lay itself out.** Correct by
  construction, but these views page at 200 rows today and are meant to grow;
  giving up virtualization to fix a layout bug trades the product's headroom for
  a CSS problem that has a CSS answer.
- **Measure the header in JS and write widths onto rows.** A resize-observer
  loop to enforce what a shared track list gives for free, with a frame of
  visible misalignment on every resize.
