## ADDED Requirements

### Requirement: A single event is readable by id and can be linked to

The system SHALL expose a read of one event of a cluster's history by its id, gated exactly as the paged read is: a caller without the read permission on the cluster gets the same 404 as for a cluster that does not exist. An id that is not in the cluster's history, whether it never existed or was reaped by retention, SHALL be answered with 404.

The events screen SHALL open the details of the event named by an `event` search parameter, including an event that is not on the loaded page, and SHALL say that the event no longer exists, and that retention may have removed it, when the id is unknown. Each row SHALL offer a "Copy link" item in its actions menu that copies the address of that event's details.

#### Scenario: An event is read by id

- **WHEN** an operator with read permission requests one event of a cluster by its id
- **THEN** the response is that event, with the same fields and governed properties as in the paged read

#### Scenario: An unknown event is a 404

- **WHEN** an operator requests an event id that is not in the cluster's history, or that belongs to another cluster
- **THEN** the response is 404

#### Scenario: A caller without a grant is told nothing

- **WHEN** a caller with no read permission on the cluster requests one of its events
- **THEN** the response is 404, the same as for a cluster that does not exist

#### Scenario: A link opens an event that is not on the page

- **WHEN** an operator opens the events screen with `?event=<id>` naming an event older than the loaded page
- **THEN** the details of that event are shown

#### Scenario: A link to an event that was reaped says so

- **WHEN** an operator opens the events screen with `?event=<id>` naming an event that no longer exists
- **THEN** the screen states that the event no longer exists and that retention may have removed it

#### Scenario: Copying a link to an event

- **WHEN** an operator chooses "Copy link" in a row's actions menu
- **THEN** the address of the events screen with `?event=` set to that row's id is copied
