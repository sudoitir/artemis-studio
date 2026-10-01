# ADR-0161: Columns are sized by a solver over measured content

- **Status**: accepted
- **Date**: 2026-10-01
- **Deciders**: Mahdi Amirabdollahi

## Context

ADR-0116 fitted free-text columns to their content as `minmax(<fit>px, 1fr)`, clamped to 180–480
px, and made every column resizable. The fit had a defect that no jsdom test could see.

- **The `scrollWidth` bug.** `measure()` read each cell's `scrollWidth`. A cell's `scrollWidth` is
  never smaller than its current box, and a free column's box was already stretched to its `1fr`
  share. The first fit therefore read back the stretched width, locked every free column at it plus
  2 px, and the sum of those floors became the grid's `min-inline-size`. That exceeded the
  container, so the grid scrolled sideways and never shrank again, even when every value was short.
  The jsdom tests mocked `scrollWidth`, so they passed.
- **Fixed floors.** A 180 px floor per free column meant five free columns needed 900 px before
  any other column, and the content box beside the navigation at a 1280 px window is about 960 px.
- **One font assumption.** Code values measured in the UI font, and a font that had not loaded yet
  measured in its fallback.
- **No order of retreat.** When columns did not fit, the only answer was horizontal scrolling, with
  the identifying column scrolled away.
- **Stale state.** Stored widths were read once, so a view that changed its `storageKey` (one view
  for several resource kinds) kept the first kind's widths.

## Decision

We will measure content in its own font and bounds, and place columns with a pure solver.

- **Column kinds set bounds and font.**

  | Kind | Bounds | Font | Overflow |
  |---|---|---|---|
  | text | 12–48 ch | Next | end ellipsis |
  | identifier | 16–64 ch | Next | middle ellipsis, keeping a tail of about 12 ch |
  | code | 12–48 ch | Mono | end ellipsis |
  | number | content | Next, tabular | end-aligned |
  | time | content | Next, tabular | none |
  | status | content | Next | none |

  A column may override the bounds with `min` and `max` in ch. Each column also has a `priority`:
  `essential`, `high` or `low`. The first data column and node-attribution columns are essential.
- **The Measurer.** An `inert`, `aria-hidden`, zero-size, clipped box holds a `max-content` grid of
  the header and a sample: the first 40 rows plus the 5 longest values of each text-like column.
  Each cell uses its kind's font and `min-inline-size` / `max-inline-size` in ch, so the width it
  reports is already clamped in the right font. Only its first row is read. Because the box is
  `max-content` and outside the grid, what it reports never depends on the grid's own width.
- **Triggers.** Measurement runs in a layout effect, before paint, on the first non-empty data, a
  change to the column set, fonts finishing loading, a density change, and an explicit refit
  (double-click on a border). Never on scroll. In a live table, new rows can only widen columns, at
  most every 2 s, and never while the pointer or focus is inside the grid or it is scrolled away from
  the top.
- **The solver.** `solveColumns({ W, cols, prev })` returns `{ template, hidden, overflow }` and is
  pure and unit-tested. In order:
  1. Widths the viewer set are hard tracks, never shrunk.
  2. If everything fits, grow columns get `minmax(base, 1fr)` and the rest get `base`.
  3. Otherwise, truncatable columns shrink in proportion toward their floors.
  4. Otherwise, `low` and then `high` columns are hidden, inline end first, with 16 px of
     hysteresis against `prev` so a column does not flicker at the threshold. Essential columns, the
     first data column, node-attribution columns and columns the viewer chose to show are never
     hidden.
  5. Otherwise, the floors are used and the table overflows. The select column, the first data
     column and the actions column become sticky; sticky cells layer the row tint over an opaque
     surface; an IntersectionObserver sentinel, not a scroll handler, shows the edge shadow.

  It reruns from one rAF-throttled ResizeObserver per table. With no width (jsdom) every column
  shows at its base. The static renderer (ADR-0160) uses the same solver.
- **Hidden columns are stated.** The Columns control always sits in the toolbar, with its space
  reserved so the solver's width does not change when it appears. Its name carries the hidden count,
  and changes are announced.
- **Clipped values stay readable**: the reveal panel (anchored with logical properties), Ctrl+C and
  `title`.
- **Resize carries forward from ADR-0116 unchanged in behaviour**: drag the header's inline-end
  border, double-click it to fit, Ctrl+Shift+Left and Ctrl+Shift+Right on a focused header cell in
  16 px steps with the new width announced, and the grid stays one tab stop.
- **Table state** is `{ v: 1, widths, hidden, shown, order }` under `as.table.<storageKey>`,
  re-read whenever the key changes. Old `as.grid.*` keys are ignored. State stays out of the URL.

This supersedes ADR-0116.

## Consequences

- A table fits a 1280 px window when its values are within bounds, and degrades in a stated order:
  shorten, hide and say so, then scroll with the identity and actions in view.
- Identifiers that share a prefix stay distinguishable when shortened, because the tail is kept.
- Measurement is independent of the grid's current width, so the `scrollWidth` lock cannot recur;
  the browser tests (ADR-0165) cover it with real layout instead of mocks.
- A sample can miss a longer value further down. The 5 longest values per column narrow that, a
  double-click refits, and clipped values stay readable.
- Hiding a column hides information. The count is always visible and announced, and a column the
  viewer shows stays shown.
- Stored widths reset once, because the key and shape changed.
- Every call site gives its columns a kind and priority instead of px widths, and builds them in a
  pure `columns.ts` factory that the browser tests import.

## Alternatives considered

- **Fix `scrollWidth` by measuring with the track at `max-content`.** Toggling the live grid's
  template forces two layouts per measure and still measures only rendered rows in whatever font
  happens to be loaded.
- **Canvas `measureText`.** Fast, but it ignores padding, icons and rendered cells, and needs the
  font state tracked by hand.
- **Always scroll horizontally.** The simplest, but the identifying column scrolls away, which is
  the failure this decision removes.
- **Wrap long values.** Rows are virtualised at a fixed height (ADR-0020).
- **TanStack Table's column sizing.** It races the DOM, as ADR-0116 found.
