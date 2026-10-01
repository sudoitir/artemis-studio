# ADR-0160: One data table with two renderers

- **Status**: accepted
- **Date**: 2026-10-01
- **Deciders**: Mahdi Amirabdollahi

## Context

`VirtualTable` was the one shared grid: CSS-grid tracks (ADR-0020), one tab stop with roving cell
focus (ADR-0108), the anchored row menu (ADR-0107), and content fitting with resize (ADR-0116). It
had 17 call sites and was exported by the SDK. About 40 other files rendered Mantine `<Table>`
directly, each with its own empty state, its own loading, its own widths and no sorting,
announcement or node attribution. ADR-0056 already requires every view to be bounded, and
`operator-ui` requires new tabular views to use the product's grid, but a small read-only list of
five rows does not need virtualisation, roving focus or a row menu, and forcing it into the grid
would make it harder to use.

Many of those raw tables were not tables at all: two columns of a term and its value.

## Decision

We will have one `DataTable`, in `web/src/ui/table/`, that takes one column model and renders it one
of two ways.

- **`variant: 'grid'`, the default**, for interactive or large data.
  - Always virtualised, on CSS-grid tracks, keeping ADR-0020, ADR-0107 and ADR-0108: one tab stop,
    roving cell focus, the row menu, selection and resize.
- **`variant: 'static'`** for a small, read-only set.
  - A native `<table>`, not virtualised, with its header sticky to the page. Cells hold natively
    focusable controls, so the keyboard needs no grid model.
  - Above 200 rows it switches to the grid on its own, so a list that grows in production never
    falls off a cliff.
- **One column model for both** (`Column<T>`: an `accessor` for the plain value, an optional `cell`
  renderer, a `kind`, a `priority`), sized by one solver (ADR-0161). A view can change renderer
  without rewriting its columns.
- **States are part of the frame.** `label` and `empty` are required. Loading renders the header and
  skeleton rows at the density's height; an error renders `ErrorState` in place; the empty, error and
  loading content is a sibling of the grid, never a child of `role="grid"`.
- **`DescriptionList`** (`<dl>`) replaces every key and value table.
- `VirtualTable` and `GridColumn` are deleted, and every raw `<Table>` in a feature moves to
  `DataTable` or `DescriptionList`.

This refines ADR-0020, whose note that Mantine `Table` is right for small in-flow tables now means
the static renderer, and ADR-0056, whose bound applies to both renderers through the switch at 200
rows.

## Consequences

- Every table in the console sizes, sorts, announces, attributes nodes and shows its states the same
  way, and a fix to one is a fix to all.
- The static renderer keeps native table semantics where they are cheap, and the grid keeps the
  ARIA grid contract where the data needs it.
- A view near the 200-row switch can change renderer as its data grows, and with it its keyboard
  model. The switch is the price of no silent cliff.
- The SDK loses `VirtualTable`, a breaking change for plugins (ADR-0163).
- Two renderers share a column model, so a column feature must be built for both or be explicitly
  grid-only, as `wrap` is static-only.

## Alternatives considered

- **A native table for the interactive grid too.** Virtualisation takes rows out of flow, and rows
  out of flow break table layout, the defect ADR-0020 removed.
- **CSS subgrid.** Absolutely positioned rows do not take part in the parent's track sizing.
- **Grid everywhere.** Five read-only rows would pay for virtualisation and a roving-focus model that
  makes their links harder to reach.
- **TanStack Table's column sizing.** It sizes a model, not the DOM, and races the DOM, as ADR-0116
  found.
- **Leave the raw tables.** They are where the inconsistencies live.
