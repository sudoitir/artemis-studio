# ADR-0108: The data grid is one tab stop with roving cell focus

- **Status**: accepted
- **Date**: 2026-09-24
- **Deciders**: Artemis Studio maintainers

## Context

`VirtualTable` declares `role="grid"`, but it implements none of the grid's keyboard
model:

- Every free-text cell is its own Tab stop, so a 200-row page of queues holds several
  hundred of them.
- Rows cannot be activated from the keyboard, so the queue drawer, the message detail and
  Flow's table twin need a pointer.
- The grid has no accessible name.
- The full-value reveal is positioned with a physical `left`.

A row action menu (ADR-0107) needs a keyboard path, and that path starts with being able
to stand on a row.

## Decision

1. **The grid is one Tab stop.** Focus moves with the WAI-ARIA APG grid keys:
   - the arrow keys move between cells, and Up/Down keep the column;
   - Home and End go to the row's first and last cell;
   - Ctrl+Home and Ctrl+End go to the first and last row;
   - Page Up and Page Down move by a page.
   
   The header row is part of the model, so sort buttons and select-all keep a keyboard
   path. The grid takes an accessible name.
2. **Focus is on cells, not rows.** Focusable rows belong to `treegrid`, and screen readers
   announce them poorly inside `grid`.
   - Enter activates the focused cell's row, as a click would.
   - Space toggles its selection where the grid is selectable.
   - Shift+F10 or the ContextMenu key opens its action menu.
3. **A cell holding exactly one control focuses that control.** A link, a Close button or a
   checkbox takes the focus itself and answers Enter and Space natively. Controls in
   inactive cells are removed from the Tab order.
4. **The active cell is tracked by row key and column**, not by index.
   - It is re-resolved after a refetch, a sort, a page change or a live prepend.
   - A vanished row hands focus to its nearest neighbour.
   - A range extractor always renders the active row, so a focused cell is never unmounted
     by the virtualizer.
   - The sticky header's height is passed as scroll padding, so a row brought into view
     is never hidden under it.
5. **A cell's full value stays reachable by keyboard.**
   - The reveal opens when a clipped cell takes focus, and is positioned with logical
     properties.
   - Ctrl/Cmd+C with nothing selected copies the focused cell's value and announces it.

## Consequences

- A keyboard operator can reach, open and act on any row. Tab now leaves a grid in one
  press instead of hundreds.
- Views gain keyboard activation without writing any. Flow's table becomes a true twin of
  the graph.
- Tests that tabbed into cell buttons walk the grid with arrows instead.
- The grid owns a small focus model that must be kept correct under virtualization. It is
  unit-tested apart from the component.

## Alternatives considered

- **Row focus.** It is simpler, but it is the treegrid pattern and poorly announced in a
  grid.
- **Keeping per-cell Tab stops and adding a per-row Actions button.** That makes the Tab
  order still worse, and still gives no way to activate a row.
- **Dropping `role="grid"` for a table with links.** It loses selection and sort semantics,
  and does not solve activation.
