# ADR-0116: Grid columns fit their content and resize

- **Status**: accepted
- **Date**: 2026-09-27
- **Deciders**: Artemis Studio maintainers

## Context

`VirtualTable` lays out header and rows as CSS grids over one track list (ADR-0107,
ADR-0108). Rows are virtualized at a fixed height, so a cell stays on one line and a value
longer than its column ends in an ellipsis. A column is either a fixed `width` or a
free-text track, `minmax(180px, 1fr)`, sharing whatever is left.

On a normal window that cuts the values an operator reads the grid for: queue names,
addresses and client ids. The reveal panel shows a cut value one cell at a time, but you
can't compare a column you can't read. There was also no way to widen a column.

## Decision

We will size columns from their content and let the viewer resize them.

- **Fit.**
  - When rows are first rendered, and when the column set changes, each free-text column
    measures its header and its rendered cells.
  - Its track becomes `minmax(<fit>px, 1fr)`, with the fit clamped to 180–480 px (180 is the
    existing free-text floor), so spare width is still shared.
  - A column with a declared `width` keeps it as its least width, and widens as a fixed track
    when a value needs more, for example `MULTICAST` in a 96 px type column.
  - Fitting never runs while scrolling, so columns do not move under the pointer.
  - A grid wider than its container scrolls horizontally.
- **Resize.**
  - A handle on each header's inline-end border drags the column. Double-clicking it fits
    the column with no cap other than the container.
  - A focused header cell takes Ctrl+Shift+Left and Ctrl+Shift+Right, 16 px a step, and the
    new width is announced.
  - The grid stays one tab stop. The handle is a pointer affordance, hidden from assistive
    technology, because the keyboard already has the action.
- **State.**
  - The grid keeps the widths a viewer set in a small map of its own. A resized column is a
    fixed track.
  - A drag starts from the header cell's rendered width.
  - A grid given a `storageKey` stores those widths per viewer in `localStorage`, under
    `as.grid.<key>`. An unreadable, invalid or stale entry is ignored.
  - Widths are a viewer preference, not view state, so they stay out of the URL (non-negotiable 9).

## Consequences

- Long values are readable by default, up to the cap. Beyond it, the reveal panel, a drag or
  a double-click reads them.
- Fitting measures only rendered rows. A longer value further down the list still ends in
  an ellipsis until the viewer widens the column. Measuring every row would defeat
  virtualization.
- Every built-in grid needs a stable `storageKey`. Renaming a key forgets the stored widths,
  which is harmless.
- Plugins get the behaviour through the SDK's `VirtualTable` with no change of their own,
  and remember widths once they pass a key.

## Alternatives considered

- **Wrapping cells.** Rows are virtualized at a fixed height, so a wrapped value would
  overflow its row.
- **Wider fixed defaults.** They waste space on short values and still cut long ones.
- **TanStack Table's column sizing and resizing features.** The grid already builds on
  TanStack Table. But its resize handler starts from `column.getSize()`, a model size, while
  a fitted `minmax(…, 1fr)` track is as wide as the CSS grid made it. Seeding the model from
  the DOM before each drag races React's state update. The grid needs the rendered width, a
  pointer delta and a map, which is about twenty lines of its own.
- **Widths in the URL.** A width is how one viewer likes to read a grid. It is not what is
  being viewed, and it would make every shared link longer.
