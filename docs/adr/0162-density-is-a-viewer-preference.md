# ADR-0162: Density is a viewer preference

- **Status**: accepted
- **Date**: 2026-10-01
- **Deciders**: Mahdi Amirabdollahi

## Context

Tables had one row height, a constant in `VirtualTable`. Some operators scan hundreds of queues and
want as many rows on screen as possible; others read a few rows at a time, at a larger zoom or on a
high-density display, and want room. The row height also feeds the virtualizer, so it cannot change
in CSS alone.

A density chosen in one table should hold in every table: an operator does not want to set it per
page.

## Decision

We will offer two densities, chosen per viewer and applied everywhere.

- **Compact** (28 px rows, the default, because this is a data-dense console) and **comfortable**
  (36 px). The heights and cell padding are tokens (ADR-0157).
- **Chosen** from the user menu, from any table's Columns menu, and from the command palette.
- **Stored** in the browser under `as:density`, read synchronously
  (`getInitialValueInEffect: false`) so the first render uses it. `boot-prefs.js` sets
  `<html data-density>` before React runs (ADR-0159), so a reload renders at the chosen height with
  no jump.
- **Applied** through `:root[data-density]`, which selects `--as-row-h` and `--as-cell-pad`, and
  through `useDensity()`, which feeds the virtualizer's row height. A density change calls
  `virtualizer.measure()`, because the virtualizer does not re-measure when `estimateSize` changes,
  and remeasures columns (ADR-0161).
- **Not view state.** Density stays out of the URL: it is how one viewer likes to read, not what is
  being viewed.
- **Target size.** Interactive controls in a compact row keep a target of at least 24 by 24 CSS
  pixels (WCAG 2.5.8), checked by axe's `target-size` rule.

## Consequences

- One choice changes every table, on every page, from the next paint.
- The density is per browser, not per account. An operator on two machines sets it twice. That
  matches the colour scheme and keeps a server setting out of a purely visual preference.
- Every table and every row control must work at both heights, which the browser tests check.
- The compact default is tighter than the old rows; operators who prefer the old feel choose
  comfortable once.

## Alternatives considered

- **Per-table density.** Inconsistent across pages and one more thing to set on each.
- **A server-side user preference.** Follows the operator across machines, but cannot apply before
  first paint without the server templating `index.html`, and adds an API for a visual choice.
- **Three densities.** A third step adds a test matrix for a difference few would notice.
- **Follow the browser zoom only.** Zoom scales everything, not just rows, and is already the
  operator's own control.
