## ADDED Requirements

### Requirement: A grid's state can be saved as a named view
The system SHALL let a user save the current filters, visible columns and sort order of any grid as a named view, and apply, rename, update and delete their own views.

#### Scenario: A user saves a view
- **WHEN** a user sets filters and columns and saves them under a name
- **THEN** the view appears in the grid's view list and applying it restores the same state

#### Scenario: A view is stale
- **WHEN** a saved view names a column or filter that no longer exists
- **THEN** the rest of the view applies and the missing part is reported, not silently dropped

### Requirement: A view has a visibility
A view SHALL be personal to its owner, shared with chosen roles, or the default for a cluster or environment. Only a user with the matching permission SHALL share a view or set a default.

#### Scenario: A view is shared with a role
- **WHEN** an owner shares a view with a role
- **THEN** users holding that role see it in their list and cannot change it

#### Scenario: A default applies
- **WHEN** a grid opens for a cluster with a default view and the user has no own default
- **THEN** the default view is applied

#### Scenario: An unprivileged user tries to set a default
- **WHEN** a user without the permission sets a default
- **THEN** the request is refused

### Requirement: A view respects the viewer's permissions
Applying a view SHALL never reveal data, columns or scopes the viewer is not permitted to see, regardless of who created or shared it.

#### Scenario: A shared view names a hidden column
- **WHEN** the viewer lacks permission for the column
- **THEN** the column is not shown and the view still applies

#### Scenario: A view targets a cluster the viewer cannot read
- **WHEN** the viewer opens the view
- **THEN** no data from that cluster is shown

### Requirement: Views can be exported and imported as JSON
The system SHALL export a view as a JSON document and import one, validating it before saving and reporting what was rejected.

#### Scenario: A view round-trips
- **WHEN** a view is exported and imported into another installation
- **THEN** it applies with the same filters, columns and sort where those exist there

#### Scenario: A malformed document is imported
- **WHEN** the JSON is invalid or names unknown fields
- **THEN** nothing is saved and the errors are listed

#### Scenario: A hostile view name
- **WHEN** an imported or shared view's name or filter holds markup or script
- **THEN** it is shown as plain text and nothing runs

#### Scenario: An oversized document
- **WHEN** an imported document exceeds the size or filter-count limit
- **THEN** it is refused before parsing its content
