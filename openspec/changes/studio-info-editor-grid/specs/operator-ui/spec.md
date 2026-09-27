## ADDED Requirements

### Requirement: Grid columns fit their content and can be resized

A data grid's columns SHALL size to their content: each column's minimum is its widest rendered value or header, up to a cap, and never less than its declared width; spare width is shared among the free-text columns. Sizing SHALL happen when rows first render and when the columns change, never while scrolling. A grid wider than its container SHALL scroll horizontally, with its header and rows aligned.

Every column SHALL be resizable:
- by dragging its header's end border;
- by double-clicking that border, which fits the column to its content;
- from the keyboard, with Ctrl+Shift+Left and Ctrl+Shift+Right on a focused header cell. The new width SHALL be announced, and the grid SHALL stay one tab stop.

Widths SHALL be remembered per viewer and per grid in browser storage when the grid names a storage key. They SHALL fall back to fitting when storage is empty, unavailable or out of date, and SHALL NOT be written to the URL. A value longer than its column SHALL still be readable in full, as the grid navigation requirement states.

#### Scenario: Long names are not cut by default
- **WHEN** a queues grid lists a queue whose name is 300 pixels wide, in a window with room for it
- **THEN** the name column is at least that wide and the name shows without an ellipsis

#### Scenario: Resizing without a pointer
- **WHEN** an operator focuses the Queue header cell and presses Ctrl+Shift+Right twice
- **THEN** the column is 32 pixels wider, the new width is announced, and pressing Tab leaves the grid

#### Scenario: A width survives a reload
- **WHEN** an operator drags a column wider and reloads the view
- **THEN** the column keeps its width, and clearing the browser's storage returns it to fitting

#### Scenario: Header and rows stay aligned
- **WHEN** a column is resized while the grid is scrolled horizontally
- **THEN** every header cell stays above its column's cells
