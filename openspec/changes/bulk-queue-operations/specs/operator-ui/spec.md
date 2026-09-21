## ADDED Requirements

### Requirement: Queues can be selected individually or by filter

The queues screen SHALL let an operator select queues row by row, and select every queue
on the page. When the page does not show every queue matching the current filter, it
SHALL offer to select all of them, stating how many that is. Once all matching queues
are selected, the screen SHALL say so, and SHALL offer to clear the selection.

When queues are selected, the screen SHALL show the number selected and the bulk actions
available for them.

Each bulk action SHALL be gated on the permission of the single-queue operation it
applies. An action the operator may not take SHALL be shown disabled, with the reason
reachable from the keyboard. While permissions are still loading, the actions SHALL be
offered.

#### Scenario: Selecting all queues matching a filter

- **WHEN** an operator filters the queues screen to `orders`, which matches 140 queues
  across three pages, and selects the page
- **THEN** the screen offers to select all 140 matching queues, and after they accept it states
  that all 140 queues matching `orders` are selected

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
`delete 37 queues`. A pause or resume SHALL be confirmed once. The option to override
the message cap SHALL be shown only when the run is over the cap and the operator may
override it. The option to continue past failures SHALL be off by default.

The confirming control SHALL be busy while the execution request is in flight, and SHALL
NOT be submitted twice. Once the run is accepted, the operator SHALL be taken to the
run's progress view.

#### Scenario: A destructive bulk run cannot be armed without typing

- **WHEN** an operator previews a delete of 37 queues
- **THEN** the preview states the queues, the nodes, and the messages destroyed, and the
  delete cannot be confirmed until `delete 37 queues` has been typed

#### Scenario: A keyboard-only operator can complete and abandon the flow

- **WHEN** an operator opens a bulk delete preview from the keyboard
- **THEN** focus moves into the dialog, escape dismisses it, and focus returns to the control
  that opened it

### Requirement: A bulk run's progress and outcome are visible and announced

A bulk run SHALL have a view at its own address. The view SHALL show:
- the run's status in words;
- its progress;
- the count of queues in each outcome;
- each queue's outcome, expandable to its per-node detail in the same form as a
  single-queue outcome.

It SHALL update live while the run executes. It SHALL offer to stop a running run, and
SHALL link to the run's audit event.

When the run finishes, the outcome SHALL be announced to assistive technology, and it
SHALL be stated in words as succeeded, partial, failed, stopped, or interrupted.

Past bulk runs on a cluster SHALL be listed, newest first, each with its operation,
queue count, who ran it, when, and its outcome.

Progress motion SHALL honour the operator's reduced-motion preference.

#### Scenario: A partial run is stated as partial

- **WHEN** a run finishes with 8 queues succeeded and 2 failed
- **THEN** the view states the run was partial, shows 8 succeeded and 2 failed, and
  announces that outcome

#### Scenario: A reloaded page shows the run in progress

- **WHEN** an operator reloads the progress view of a run that is still executing
- **THEN** the view shows the run's current progress and continues to update
