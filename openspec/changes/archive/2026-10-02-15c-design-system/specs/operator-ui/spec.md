## MODIFIED Requirements

### Requirement: Grid columns fit their content and can be resized

A data table's columns SHALL size to their content: each column's width is its widest rendered value among the loaded rows or its header, within bounds set by the kind of value it holds. Spare width SHALL be shared among the free-text columns.

In a window 1280 pixels wide or wider at 100% zoom, a table SHALL fit its container without horizontal scrolling when its values are within their columns' bounds. When it does not fit, the table SHALL, in this order:
1. shorten the longest values, keeping each one readable in full on demand, and shortening identifiers in the middle so that names sharing a prefix stay distinguishable;
2. hide its least important columns, starting at the inline end, while stating how many are hidden and offering to show them; the column that identifies a row and any column that names the node a row came from SHALL NOT be hidden;
3. only then scroll horizontally, keeping the identifying column, the selection column and the actions column in view, with header and rows aligned.

Sizing SHALL happen when rows first render, when the columns change, when the typefaces finish loading and when the density changes. It SHALL NOT happen while the table scrolls, and new rows arriving in a live table SHALL NOT reflow its columns while the operator is reading or scrolling it.

Every column SHALL be resizable:
- by dragging its header's end border;
- by double-clicking that border, which fits the column to its content;
- from the keyboard, with Ctrl+Shift+Left and Ctrl+Shift+Right on a focused header cell. The new width SHALL be announced, and the table SHALL stay one tab stop.

A width the operator set SHALL be kept exactly; fitting SHALL NOT shrink it. Widths, the columns the operator hid or showed, and column order SHALL be remembered per viewer and per table in browser storage when the table names a storage key. They SHALL fall back to fitting when storage is empty, unavailable or out of date, and SHALL NOT be written to the URL.

#### Scenario: Long names are not cut by default
- **WHEN** a queues table lists a queue whose name is 300 pixels wide, in a window with room for it
- **THEN** the name column is at least that wide and the name shows without being shortened

#### Scenario: A table fits a 1280-pixel window
- **WHEN** the queues table shows typical queues in a 1280 by 800 window with the navigation expanded
- **THEN** the table has no horizontal scroll bar and every column is visible

#### Scenario: Columns that do not fit are hidden and named
- **WHEN** the columns' shortest widths together exceed the table's width
- **THEN** the least important columns are hidden, the table states how many are hidden, and the operator can show any of them

#### Scenario: A column the operator shows stays shown
- **WHEN** an operator shows a hidden column
- **THEN** it stays visible after a reload, and another column is hidden or the table scrolls instead

#### Scenario: The identifying column stays in view while scrolling
- **WHEN** a table scrolls horizontally
- **THEN** its identifying, selection and actions columns stay in view and every header cell stays above its column

#### Scenario: Header and rows stay aligned
- **WHEN** a column is resized while the table is scrolled horizontally
- **THEN** every header cell stays above its column's cells

#### Scenario: Identifiers that share a prefix stay distinguishable
- **WHEN** two queue names differ only in their last characters and both are shortened
- **THEN** the shortened names show those last characters

#### Scenario: A live table does not reflow under the reader
- **WHEN** new rows with longer values arrive while the operator is scrolled into a live table
- **THEN** the column widths do not change until the operator returns to the top or leaves the table

#### Scenario: Resizing without a pointer
- **WHEN** an operator focuses the Queue header cell and presses Ctrl+Shift+Right twice
- **THEN** the column is 32 pixels wider, the new width is announced, and pressing Tab leaves the table

#### Scenario: A width survives a reload
- **WHEN** an operator drags a column wider and reloads the view
- **THEN** the column keeps its width, and clearing the browser's storage returns it to fitting

### Requirement: New views reuse the product's existing view, token, and confirmation machinery

Tabular views SHALL use the product's data table: its interactive grid for large or interactive data, or its static table for a small read-only set, with its sizing, sorting, sort-state announcement, density and node attribution, rather than a new table implementation. Pairs of terms and values SHALL use the product's description list.

Views SHALL be composed from the product's page parts: one page header, sections, toolbars, and the empty, error and loading states.

Confirmations SHALL use the product's confirmation dialog, and destructive confirmations its typed-confirmation component, rather than a further hand-rolled copy.

Colour, type, spacing, radius, elevation, motion and density SHALL come from the product's semantic token layer. A raw colour literal SHALL NOT appear in a component.

Layout SHALL use logical properties, never physical ones.

State that describes what is being viewed — filters, selection, paging, the open resource — SHALL live in the URL so a view can be shared and restored, while transient interaction state stays local.

#### Scenario: A new table is not a new table implementation
- **WHEN** a new tabular view is added
- **THEN** it uses the product's data table and inherits its behaviour

#### Scenario: Sorting is announced
- **WHEN** an operator sorts a table by a column
- **THEN** the sorted column and direction are announced once the sorted rows have loaded

#### Scenario: A shared view reopens as it was left
- **WHEN** an operator shares the address of a filtered, sorted view
- **THEN** the recipient sees the same filter and sort

### Requirement: A view teaches what is missing when it has nothing to show

An empty view SHALL state what the resource is, why there is none, and the action that creates one where the operator is permitted to take it. A view whose empty state reads only that there is nothing SHALL NOT be shipped.

An empty result caused by a filter SHALL be distinguishable from one caused by there being nothing at all, and SHALL offer to clear the filter.

An empty view that is empty because a node could not be reached SHALL say so rather than presenting an absence as a fact.

A view that is loading SHALL show where its content will appear, at that content's size, so the page does not move when it arrives. A view whose data failed to load SHALL state the cause and the next action in place of its content, and SHALL offer to retry where retrying can help.

#### Scenario: Nothing to show teaches what would be shown
- **WHEN** a view has no rows
- **THEN** it explains what the resource is and how one comes to exist

#### Scenario: Filtered-empty is not the same as empty
- **WHEN** a filter excludes every row
- **THEN** the view says so and offers to clear the filter

#### Scenario: Unreachable is not empty
- **WHEN** rows are absent because a node could not be reached
- **THEN** the view reports the unreachable node rather than showing an empty result

#### Scenario: Loading does not move the page
- **WHEN** a table's rows arrive after it was shown loading
- **THEN** nothing above or beside the table moves

#### Scenario: A failure names its cause and the next step
- **WHEN** a view's data request is refused because the operator lacks a permission
- **THEN** the view names the missing permission instead of an empty or generic failure

### Requirement: The operator chooses the colour scheme from the header

The header SHALL offer, on every authenticated screen, one control that cycles the colour scheme through following the operating system, light and dark. The command palette SHALL offer the same action. The control's accessible name SHALL state the scheme it switches to and, while it follows the operating system, the scheme the system is using. The choice SHALL be remembered in that browser across reloads. A first visit SHALL follow the operating system. The page SHALL open in the chosen or system scheme without first showing the other one.

#### Scenario: A first visit follows the system
- **WHEN** an operator whose operating system uses a light scheme opens the console for the first time
- **THEN** the console shows the light scheme

#### Scenario: Switching the scheme
- **WHEN** an operator activates the control while it follows the operating system
- **THEN** the console uses the light scheme regardless of the system, and the control now offers the dark scheme

#### Scenario: The choice survives a reload
- **WHEN** an operator chooses the dark scheme and reloads
- **THEN** the console opens in the dark scheme with no light frame first

## ADDED Requirements

### Requirement: The viewer chooses table density

The console SHALL offer two table densities, compact and comfortable, chosen by the viewer from the user menu, from any table's column menu and from the command palette. The choice SHALL apply to every table, be remembered in that browser, take effect before the first paint of the next page load, and SHALL NOT be written to the URL. Interactive controls in a compact table SHALL keep a target of at least 24 by 24 CSS pixels.

#### Scenario: Density applies everywhere
- **WHEN** an operator switches to the comfortable density on the queues page and opens the audit page
- **THEN** the audit table uses the comfortable density too

#### Scenario: Density survives a reload without a jump
- **WHEN** an operator who chose comfortable reloads a page with a table
- **THEN** the table first renders at the comfortable row height

### Requirement: The console is laid out for desktop windows and stays usable when zoomed

The console SHALL be laid out for desktop windows 1280 pixels wide and wider, and SHALL NOT scroll the page horizontally in a 1280 by 800 window at 100% zoom with the navigation expanded. It SHALL NOT offer separate layouts for phones or tablets. At 200% zoom it SHALL remain usable with no content or function lost: page content wraps, and the navigation collapses to its icon rail. Data tables, graph canvases and the code editor MAY scroll in two dimensions.

#### Scenario: No sideways page scroll at 1280
- **WHEN** any page is shown in a 1280 by 800 window
- **THEN** the page has no horizontal scroll bar

#### Scenario: Zoomed to 200%
- **WHEN** an operator zooms a 1280-pixel window to 200%
- **THEN** every control on the page can still be reached and read, and the navigation shows its icon rail

### Requirement: Every page states what it is with one heading

Every page SHALL have exactly one top-level heading that names the view, and its section headings SHALL follow it without skipping a level. The cluster a page belongs to SHALL be stated next to that heading, not as a second top-level heading.

#### Scenario: A cluster page has one top-level heading
- **WHEN** an operator opens the queues page of a cluster
- **THEN** the page's only top-level heading is "Queues", and the cluster's name and environment are shown beside it

### Requirement: The console's typefaces ship with Studio

The console SHALL render with the typefaces it ships with, served from Studio itself. It SHALL NOT request fonts, scripts or styles from any other host, so it renders the same on every machine and on installations without internet access.

#### Scenario: An air-gapped installation renders the console's typefaces
- **WHEN** the console is opened on an installation with no internet access
- **THEN** it renders in its own typefaces and makes no request to another host

### Requirement: The SQL console is one workspace for writing, costing and reading a query

The SQL console SHALL show the query editor and its results in one workspace whose split the operator can resize, remembered per browser. Before a query runs, the console SHALL state its estimated cost in words and numbers, and an estimate that is not available SHALL be stated as unavailable, never as zero. A running query SHALL show its progress and SHALL be cancellable from a button and from the keyboard. A query error SHALL be shown at its position in the editor as well as in words. Results SHALL use the product's data table, with column visibility and order chosen from its column menu. Escape SHALL keep moving focus out of the editor.

#### Scenario: Cost before running
- **WHEN** an operator writes a query that scans a large queue
- **THEN** the console states the estimated cost before the query is run

#### Scenario: Cancel a running query from the keyboard
- **WHEN** a query is running and the operator presses Mod+.
- **THEN** the query is cancelled and the console says so

#### Scenario: An error points at its position
- **WHEN** a query has a syntax error
- **THEN** the error is marked at its position in the editor and described in words

### Requirement: Topology states each node's role and health in words

The Topology view SHALL show, for every broker node, its high-availability role, its liveness, the node it is paired with and its version, with the state in words and colour only as emphasis. An unreachable node SHALL be shown as unreachable. Above the level-of-detail bound the view SHALL say what it is not showing. The view SHALL be operable from the keyboard, SHALL offer the same information as a table, and SHALL teach how to register a cluster when there is none.

#### Scenario: A backup node is labelled
- **WHEN** a cluster has a primary and a backup node
- **THEN** each node is labelled with its role and its pair in words

#### Scenario: The topology as a table
- **WHEN** an operator switches the Topology view to its table
- **THEN** every node, role, pairing, liveness and version shown in the graph is listed
