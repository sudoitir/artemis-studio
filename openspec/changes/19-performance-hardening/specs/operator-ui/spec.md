## ADDED Requirements

### Requirement: Large lists render in time independent of their length
Every grid that can list more than a few hundred rows SHALL render only what is visible, so that opening and scrolling it stays responsive at the sizes in the sizing guide. Measuring column widths SHALL NOT cost more as the list grows.

#### Scenario: A very large grid opens
- **WHEN** a grid receives 50,000 rows
- **THEN** it becomes interactive within the guide's stated time, only a bounded number of rows are in the page, and scrolling holds the guide's stated frame rate

#### Scenario: Keyboard and selection still work
- **WHEN** a user selects rows or moves with the keyboard in a large grid
- **THEN** focus and selection behave as in a small grid

### Requirement: A virtualized grid can be searched in place
A grid that renders only visible rows SHALL provide its own find, which searches all rows and not only those in the page, shows the number of matches, and moves to each match, since the browser's find cannot see rows that are not rendered.

#### Scenario: A match is off screen
- **WHEN** a user searches a large grid for a value in a row that is not rendered
- **THEN** the grid shows the match count and scrolls to the row

#### Scenario: No match
- **WHEN** a search matches no row
- **THEN** the grid says there are no matches

### Requirement: A view that needs many broker reads makes them in batches
Views that read the same kind of value from many resources SHALL request them in batches of at most 200 rather than one call per resource, within the existing rate limits. Broker calls SHALL be counted in a metric by kind.

#### Scenario: A large cluster view loads
- **WHEN** a view covers 1,000 queues
- **THEN** it makes five batched calls of at most 200, not 1,000 calls

#### Scenario: Calls are visible
- **WHEN** an operator reads the metrics
- **THEN** broker calls are counted by kind
