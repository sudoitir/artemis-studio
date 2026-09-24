## ADDED Requirements

### Requirement: A row's actions are offered in one menu reachable by pointer and keyboard

Every grid that lists broker resources SHALL offer, for each row, one menu of the actions
that apply to that resource. The menu SHALL open:
- by right-click on the row;
- by a visible Actions control on the row, which has an accessible name naming the row;
- by Shift+F10 or the ContextMenu key while a cell of the row has focus.

Right-click SHALL leave the browser's own menu in place on a link or an input, while Shift
is held, and while text is selected.

The menu SHALL group its items into Open, Copy, Operate and Destroy, in that order, and
SHALL offer the actions of every enabled feature that contributes one. An action the
operator may not take, or that the connection cannot perform, SHALL remain in the menu,
reachable by keyboard, with its reason. Activating it SHALL explain the reason in full,
including the `broker.xml` that enables it where one exists.

An action SHALL open the same previewed, confirmed flow as the screen that owns it. Its
outcome SHALL remain visible after the row leaves the grid. On dismissal, focus SHALL
return to the row's Actions control, or to the grid when the row is gone.

#### Scenario: Deleting a queue from the keyboard

- **WHEN** an operator tabs into the queues grid, moves to the row `orders`, presses
  Shift+F10, chooses "Delete queue…" and presses Escape in the dialog
- **THEN** the menu opens naming `orders`, the delete dialog opens with its preview, and
  focus returns to the Actions control of `orders`

#### Scenario: A blocked action explains itself

- **WHEN** an operator without the delete permission opens a queue's menu
- **THEN** "Delete queue…" is listed as unavailable with its reason, can be reached with
  the arrow keys, and activating it shows the full reason

#### Scenario: The outcome survives the row

- **WHEN** an operator closes a connection from its row and the refreshed listing no longer
  contains it
- **THEN** the close outcome remains on screen until the operator dismisses it

#### Scenario: Copying still works

- **WHEN** an operator selects text in a row and right-clicks it
- **THEN** the browser's own menu opens

### Requirement: A data grid is one tab stop navigated with the arrow keys

A grid SHALL be one stop in the tab order, named for what it lists. Within it:
- the arrow keys SHALL move between cells, and Up and Down SHALL keep the column;
- Home and End SHALL move to the row's first and last cell;
- Ctrl+Home and Ctrl+End SHALL move to the first and last row;
- Page Up and Page Down SHALL move by a page.

The header row SHALL be reachable the same way. Enter SHALL activate the focused row as a
click would. Space SHALL toggle the row's selection where the grid is selectable. A cell
holding a single control SHALL give it focus.

Focus SHALL NOT be lost when the grid refreshes, re-sorts or scrolls. A focused cell whose
value is clipped SHALL show its full value. Its value SHALL be copyable from the keyboard,
and the copy SHALL be announced.

#### Scenario: Opening a queue without a pointer

- **WHEN** an operator tabs into the queues grid, presses Down twice and Enter
- **THEN** the second queue's detail opens

#### Scenario: One press leaves the grid

- **WHEN** focus is in a grid and the operator presses Tab
- **THEN** focus moves to the next control after the grid

#### Scenario: Focus survives a refresh

- **WHEN** a focused row moves position because the listing refreshed
- **THEN** focus stays on that row

### Requirement: Related resources link to each other

Wherever a view names a queue, address, session or connection that another view
presents, the name SHALL be a link to that resource. The link SHALL open the exact
resource, not a list filtered by its name, even when the resource is not on the first page.
When the feature presenting the resource is disabled, the name SHALL be plain text.

#### Scenario: From a consumer to its queue

- **WHEN** an operator activates the queue name on a consumer row
- **THEN** the queue's detail opens

### Requirement: The console states where the operator is

Every page SHALL carry a document title naming the open resource where there is one, the
view, the cluster and the product. Every cluster view SHALL show a breadcrumb of the
cluster, the view's group, the view and the open resource. The last crumb SHALL be marked
as the current page.

#### Scenario: A tab says what it holds

- **WHEN** an operator opens the queue `orders` on the Queues view of cluster `prod-eu`
- **THEN** the document title reads `orders · Queues · prod-eu · <product>`

### Requirement: Switching cluster keeps the current view

Choosing another cluster SHALL open the same view on that cluster, without the current
view's filters and open resource.

#### Scenario: Comparing queues across clusters

- **WHEN** an operator on the Queues view of `prod-eu` chooses `prod-us`
- **THEN** the Queues view of `prod-us` opens

### Requirement: The command palette finds resources without loading the brokers

The command palette SHALL:
- be openable from a visible control;
- search queues as the operator types, opening the chosen queue itself;
- offer to search the live-read resources (connections, consumers, sessions, producers,
  addresses) for the typed text, by navigating to their view;
- list the views the operator recently opened.

It SHALL NOT read from any broker to answer a keystroke, and SHALL NOT fetch while it is
closed. A view the operator lacks permission for SHALL be listed as unavailable, with the
reason.

#### Scenario: Jumping to a queue

- **WHEN** an operator opens the palette and types `ord`
- **THEN** queues matching `ord` are listed, and choosing one opens that queue

#### Scenario: A closed palette is silent

- **WHEN** the palette has not been opened
- **THEN** it issues no request

### Requirement: Single-key shortcuts are listed and can be turned off

The console SHALL list every keyboard shortcut in a help dialog, opened by `?` and from the
palette and the header. It SHALL offer `g` followed by a view's letter to go to that view,
and `/` to focus the current view's filter. Single-key shortcuts SHALL be ignored:
- while typing;
- with a modifier;
- inside a dialog or a menu.

They SHALL NOT take a key the browser reserves. A personal setting SHALL turn them off.

#### Scenario: Going to queues

- **WHEN** an operator presses `g` then `q` outside an input
- **THEN** the Queues view of the open cluster opens

#### Scenario: Never inside a confirmation

- **WHEN** a destructive confirmation dialog is open and the operator presses `g` then `q`
- **THEN** nothing navigates

#### Scenario: Turned off

- **WHEN** an operator turns single-key shortcuts off and presses `g` then `q`
- **THEN** nothing happens

## MODIFIED Requirements

### Requirement: A bulk run is confirmed against its preview and states its blast radius

Choosing a bulk action SHALL show its preview before the action can be confirmed. The
preview SHALL state, in words:
- the operation;
- the number of queues;
- the number of nodes;
- for purge and delete, the messages that will be destroyed, or that the figure is
  unavailable.

It SHALL list every queue with its per-queue figures. It SHALL list refused queues and
their reasons. It SHALL offer to show only the queues with a refusal or a warning.

A purge or delete SHALL be confirmed by typing the action and the count, e.g.
`delete 37 queues`. A run over exactly one queue SHALL instead be confirmed by typing
that queue's name. A pause or resume SHALL be confirmed once. The option to override
the message cap SHALL be shown only when the run is over the cap and the operator may
override it. The option to continue past failures SHALL be off by default.

The confirming control SHALL be busy while the execution request is in flight, and SHALL
NOT be submitted twice. Once the run is accepted, the operator SHALL be taken to the
run's progress view.

#### Scenario: A destructive bulk run cannot be armed without typing

- **WHEN** an operator previews a delete of 37 queues
- **THEN** the preview states the queues, the nodes, and the messages destroyed, and the
  delete cannot be confirmed until `delete 37 queues` has been typed

#### Scenario: A one-queue purge is confirmed by name

- **WHEN** an operator previews a purge of the one queue `orders`
- **THEN** the purge cannot be confirmed until `orders` has been typed

#### Scenario: A keyboard-only operator can complete and abandon the flow

- **WHEN** an operator opens a bulk delete preview from the keyboard
- **THEN** focus moves into the dialog, escape dismisses it, and focus returns to the control
  that opened it
