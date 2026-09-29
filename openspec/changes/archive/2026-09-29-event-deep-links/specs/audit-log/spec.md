## ADDED Requirements

### Requirement: A single audit event is readable by id and can be linked to

The system SHALL expose a read of one audit event of a cluster by its id, gated exactly as the filtered read is: a caller without the read permission on the cluster gets the same 404 as for a cluster that does not exist. An id that is not among the cluster's audit events, whether it never existed or was removed, SHALL be answered with 404.

The audit screen SHALL open the details of the event named by an `event` search parameter, including an event that is not on the loaded page, and SHALL say that the event no longer exists when the id is unknown. Each row SHALL offer a "Copy link" item in its actions menu that copies the address of that event's details.

#### Scenario: An audit event is read by id

- **WHEN** an operator with read permission requests one audit event of a cluster by its id
- **THEN** the response is that event, with the same fields as in the filtered read

#### Scenario: An unknown audit event is a 404

- **WHEN** an operator requests an audit event id that does not exist, or that belongs to another cluster
- **THEN** the response is 404

#### Scenario: A caller without a grant is told nothing

- **WHEN** a caller with no read permission on the cluster requests one of its audit events
- **THEN** the response is 404, the same as for a cluster that does not exist

#### Scenario: A link opens an audit event that is not on the page

- **WHEN** an operator opens the audit screen with `?event=<id>` naming an event older than the loaded page
- **THEN** the details of that event are shown

#### Scenario: A link to an audit event that does not exist says so

- **WHEN** an operator opens the audit screen with `?event=<id>` naming an event that does not exist
- **THEN** the screen states that the event no longer exists

#### Scenario: Copying a link to an audit event

- **WHEN** an operator chooses "Copy link" in a row's actions menu
- **THEN** the address of the audit screen with `?event=` set to that row's id is copied
