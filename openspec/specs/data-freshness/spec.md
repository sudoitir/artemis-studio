# data-freshness Specification

## Purpose
TBD - created by archiving change 07-data-freshness-and-liveness. Update Purpose after archive.

## Requirements

### Requirement: Every screen states whether it is live and when it last updated

The system SHALL present, on every authenticated screen and in the same place, an
indicator of the liveness of the data on that screen and how long ago that data
was last received. The indicator SHALL be derived from the queries the current
screen depends on, so that no screen can omit it.

Liveness SHALL be reported as one of: connected to the live stream, updating by
periodic refetch only, attempting to reconnect, unable to reach the server, or
paused by the operator.

#### Scenario: A screen that polls reports its age

- **WHEN** an operator opens a screen whose data refetches periodically
- **THEN** the indicator reports how long ago the newest data on that screen
  arrived, and updates as time passes

#### Scenario: A screen that does not poll still reports its age

- **WHEN** an operator opens a screen whose data is fetched once and not refetched
- **THEN** the indicator reports how long ago that data arrived, rather than
  omitting the screen

#### Scenario: The absolute time is available

- **WHEN** an operator inspects the elapsed-time label
- **THEN** the absolute local time of the last update is available without
  navigating away

### Requirement: The liveness state distinguishes stream health from data health

The system SHALL report the live stream being unavailable separately from data
being unavailable. A screen whose stream is down but whose periodic refetch is
succeeding SHALL NOT be reported as offline.

#### Scenario: Stream down, queries succeeding

- **WHEN** the live stream cannot connect but the screen's queries return
  successfully
- **THEN** the indicator reports that updates are arriving by periodic refetch,
  not that the application is offline

#### Scenario: Queries failing

- **WHEN** the queries the current screen depends on are failing
- **THEN** the indicator reports that the server cannot be reached

### Requirement: State changes are announced, elapsed time is not

The system SHALL announce a change of liveness state to assistive technology
politely, and SHALL NOT announce each update of the elapsed-time label.

#### Scenario: A transition is announced

- **WHEN** the liveness state changes from connected to reconnecting
- **THEN** the change is announced politely

#### Scenario: Ticking is silent

- **WHEN** the elapsed-time label advances while the state is unchanged
- **THEN** nothing is announced

### Requirement: An operator can refresh the current screen on demand

The system SHALL provide a control that refetches the data the current screen
depends on, SHALL indicate while that refetch is in progress, and SHALL make the
same action available from the command palette. The control SHALL NOT be bound to
a keyboard shortcut reserved by the browser.

The control's in-progress indication SHALL reflect only a refetch the operator
initiated, and SHALL NOT reflect periodic or stream-driven refetching, so that the
indication means the operator's action is still running. Reporting all fetching
remains the liveness indicator's responsibility, not the control's.

While an operator-initiated refetch is in progress the control SHALL NOT be
re-submittable, and further activation SHALL NOT cancel, restart or duplicate the
refetch already running.

#### Scenario: Refresh on a screen that does not poll

- **WHEN** an operator activates refresh on a screen whose data is not refetched
  periodically
- **THEN** that screen's data is refetched and the elapsed-time label resets

#### Scenario: Refresh is reachable from the palette

- **WHEN** an operator opens the command palette and chooses the refresh command
- **THEN** the current screen's data is refetched

#### Scenario: A periodic refetch does not make the control look busy

- **WHEN** a screen's data is refetched on its own interval, with no operator action
- **THEN** the refresh control does not indicate that a refresh is in progress,
  while the liveness indicator still reports that data is being fetched

#### Scenario: Repeated activation does not multiply requests

- **WHEN** an operator activates refresh repeatedly while a refresh is still running
- **THEN** no additional refetch is issued and the running one is not cancelled or
  restarted

### Requirement: An operator can pause automatic refreshing

The system SHALL provide a control that suspends automatic refetching, SHALL report
the paused state in the same indicator on every screen, and SHALL report when data
is known to have changed while paused. Pausing SHALL NOT persist across a reload.

Automatic refetching means every refetch the operator did not ask for: periodic
refetching, refetching triggered by the live stream, and refetching triggered by a
view being opened. A query that has never resolved SHALL still be fetched when it is
first observed, since suspending it would present an operator with an empty screen
rather than a held one.

The paused state SHALL be distinguishable in the control itself and not only in
assistive-technology state, and SHALL NOT be carried by colour alone.

#### Scenario: Paused screens stop refetching

- **WHEN** an operator pauses automatic refreshing
- **THEN** periodic refetching stops and the indicator reports the paused state on
  every screen

#### Scenario: Navigating while paused stays paused

- **WHEN** an operator pauses automatic refreshing and then opens a different view
  whose data is already held
- **THEN** that data is not refetched, and the indicator continues to report the
  paused state

#### Scenario: A view with no data at all still loads while paused

- **WHEN** an operator pauses automatic refreshing and then opens a view whose data
  has never been fetched
- **THEN** that data is fetched once, so the view is not presented as empty

#### Scenario: The paused control is visibly paused

- **WHEN** automatic refreshing is paused
- **THEN** the control renders differently from its running state, by more than
  colour alone

#### Scenario: Change arrives while paused

- **WHEN** the live stream signals that data has changed while refreshing is paused
- **THEN** the indicator reports that new data is available, and the data is not
  refetched until the operator resumes or refreshes

#### Scenario: Pause does not survive a reload

- **WHEN** an operator pauses automatic refreshing and then reloads the
  application
- **THEN** automatic refreshing is active again
