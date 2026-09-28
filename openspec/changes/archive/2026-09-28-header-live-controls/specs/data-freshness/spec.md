## REMOVED Requirements

### Requirement: An operator can refresh the current screen on demand

**Reason**: The live stream, periodic refetching and resuming from pause keep a screen current. The control added a header button and a palette command for an action the operator rarely needed (ADR-0118).
**Migration**: Pause and resume to refetch the current screen, or reload.

## MODIFIED Requirements

### Requirement: An operator can pause automatic refreshing

The system SHALL provide a control that suspends automatic refetching, SHALL report
the paused state in the same indicator on every screen, and SHALL report when data
is known to have changed while paused. Pausing SHALL NOT persist across a reload.

Automatic refetching means every refetch the operator did not ask for: periodic
refetching, refetching triggered by the live stream, and refetching triggered by a
view being opened. A query that has never resolved SHALL still be fetched when it is
first observed, since suspending it would present an operator with an empty screen
rather than a held one.

Pausing SHALL apply to every periodic refetch on the screen, whoever declared it,
including a runtime plugin's, with nothing required of the query's author. No periodic
refetch SHALL start after the operator pauses. Resuming SHALL refetch the data the
current screen depends on.

The paused state SHALL be distinguishable in the control itself and not only in
assistive-technology state, and SHALL NOT be carried by colour alone.

#### Scenario: Paused screens stop refetching

- **WHEN** an operator pauses automatic refreshing
- **THEN** periodic refetching stops and the indicator reports the paused state on
  every screen

#### Scenario: Pause covers a query that did not opt in

- **WHEN** a screen shows data from a query declared with a plain refetch interval,
  such as a plugin's, and the operator pauses
- **THEN** that query is not refetched until the operator resumes

#### Scenario: Pause takes effect at once

- **WHEN** an operator pauses while an interval is armed
- **THEN** no periodic refetch starts after the pause

#### Scenario: Resuming refetches the screen

- **WHEN** an operator resumes automatic refreshing
- **THEN** the current screen's data is refetched and periodic refetching starts again

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
  refetched until the operator resumes

#### Scenario: Pause does not survive a reload

- **WHEN** an operator pauses automatic refreshing and then reloads the
  application
- **THEN** automatic refreshing is active again
