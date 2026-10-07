# Spec Delta: operator-ui

## MODIFIED Requirements

### Requirement: The SQL console is one workspace for writing, costing and reading a query

The SQL console SHALL show the query editor and its results in one workspace that fills the
window below the header, whose split the operator can resize, remembered per browser. The page
itself SHALL NOT scroll; the result grid SHALL scroll inside its pane with its header in view.
The operator SHALL be able to maximise the results over the editor, from a button and from the
keyboard, and restore them with the same control or Escape. Before a query runs, the console
SHALL state its estimated cost in words and numbers, and an estimate that is not available SHALL
be stated as unavailable, never as zero. A running query SHALL show its progress and SHALL be
cancellable from a button and from the keyboard. A query error SHALL be shown at its position in
the editor as well as in words. Results SHALL use the product's data table, with column
visibility and order chosen from its column menu. Escape SHALL keep moving focus out of the
editor when the results are not maximised.

#### Scenario: Cost before running
- **WHEN** an operator writes a query that scans a large queue
- **THEN** the console states the estimated cost before the query is run

#### Scenario: Cancel a running query from the keyboard
- **WHEN** a query is running and the operator presses Mod+.
- **THEN** the query is cancelled and the console says so

#### Scenario: An error points at its position
- **WHEN** a query has a syntax error
- **THEN** the error is marked at its position in the editor and described in words

#### Scenario: A large result is read in place
- **WHEN** a query returns 500 rows in a 1280 by 800 window
- **THEN** the grid scrolls through all 500 rows inside its pane, its header stays in view, and the page does not scroll

#### Scenario: Results maximised
- **WHEN** an operator maximises the results and then presses Escape
- **THEN** the grid fills the workspace while maximised, and Escape restores the editor and the previous split

## ADDED Requirements

### Requirement: A long name never collapses its column

A name a broker supplies (a queue, an address, a match pattern, a role) SHALL be shown on one
line in tables and lists, shortened in the middle when it does not fit and readable in full on
demand, and copyable. Such a column SHALL keep a minimum width that fits a readable name, so
that no layout ever breaks a name into one word or a few characters per line. Names MAY wrap
only in a detail view that has the whole width for them.

#### Scenario: A 200-character queue name in the configuration view
- **WHEN** the configuration view lists a queue whose name is 200 characters with no spaces
- **THEN** the name occupies one line, shortened in the middle, the full name is available on demand, and no other cell in the row wraps character by character

### Requirement: A plugin page is never covered by the host

A plugin's page SHALL be laid out in the same content area as Studio's own pages, below the
header and beside the navigation, so that nothing the host draws covers any part of it,
including its first element, in either colour scheme and when zoomed to 200%.

#### Scenario: A notice at the top of a plugin page
- **WHEN** a plugin page begins with a notice
- **THEN** the whole notice is visible below the header without scrolling

### Requirement: Plugins align their fields with the host's field row

The plugin SDK SHALL export the field row that Studio's own forms use, so that fields on one
line keep their input boxes aligned whatever labels, descriptions or messages they carry, and the
notice Studio's own pages use, so that a plugin's page-level notices read, contrast and announce
like Studio's.

#### Scenario: A plugin notice is announced like Studio's
- **WHEN** a plugin shows the SDK's notice with a warning tone
- **THEN** its title is stated in words and it is announced as a status

#### Scenario: A field with a description beside one without
- **WHEN** a plugin places a field with a description beside a field without one in a field row
- **THEN** both input boxes start on the same line
