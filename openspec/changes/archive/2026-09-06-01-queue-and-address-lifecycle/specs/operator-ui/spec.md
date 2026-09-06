## ADDED Requirements

### Requirement: A destructive action states its blast radius before it can be armed

Before a destructive operation can be confirmed, the system SHALL state what will be
affected in concrete terms — the resource, the nodes, and the count of data that
will be destroyed — and SHALL NOT present a confirmation whose text is generic.

The confirmation SHALL require the operator to type the name of the resource being
acted on, and SHALL arm only on an exact match. It SHALL NOT arm on a checkbox, a
second click, or a countdown.

Where an estimate is unavailable, the confirmation SHALL say that the count is
unknown rather than omitting it, because an absent number reads as zero.

#### Scenario: The consequence is named, not implied

- **WHEN** an operator is asked to confirm a destructive operation
- **THEN** the confirmation names the resource, the nodes it will apply to, and how
  much data will be destroyed

#### Scenario: An unknown count is stated as unknown

- **WHEN** the affected count cannot be estimated
- **THEN** the confirmation says so rather than omitting the figure

#### Scenario: Confirmation requires the name

- **WHEN** an operator has not typed the exact name of the resource
- **THEN** the confirming control is not armed

### Requirement: Every mutation renders four outcomes, not one

Every mutating screen SHALL implement, and be reviewable against, the pending,
succeeded, failed, and partially-succeeded outcomes. A screen that renders only the
successful path SHALL NOT be considered complete.

While a mutation is in flight the initiating control SHALL be busy and SHALL NOT be
re-submittable, so that a slow broker cannot produce a duplicated command.

The outcome SHALL be announced to assistive technology through a live region, not
only rendered visually, because an operator using a screen reader otherwise receives
no signal that a destructive action completed.

A failure SHALL state its cause and the next action available. A message that
reports only that something went wrong SHALL NOT be shipped.

#### Scenario: A slow command cannot be double-submitted

- **WHEN** an operator submits a command and the broker is slow to answer
- **THEN** the control is busy and a second submission is not possible

#### Scenario: An outcome is announced, not only drawn

- **WHEN** a mutation completes or fails
- **THEN** its outcome is announced through a live region

#### Scenario: A failure names its cause and the way forward

- **WHEN** a mutation fails
- **THEN** the message states why it failed and what the operator can do next

### Requirement: A per-node outcome is presented as one object, legible at a glance

Where an operation applies across nodes, the system SHALL present its result as a
single per-node summary rather than as a list of separate notifications, and SHALL
make a partial result distinguishable from a complete one without the operator
reading each row.

Each node's state SHALL be carried by text as well as by colour, because colour
alone cannot convey it. Colour SHALL be used only where something is wrong,
consistent with this product's existing convention that a healthy view is
near-monochrome.

Counts SHALL be rendered with tabular figures so that per-node numbers align in a
column and a difference between nodes is visible without reading the digits.

The same summary SHALL be used for a preview and for a result, so that what the
operator confirmed and what happened are visually comparable.

#### Scenario: A partial result is obvious without reading rows

- **WHEN** an operation succeeds on some nodes and fails on others
- **THEN** the summary makes the partial outcome apparent before any individual row
  is read

#### Scenario: State does not depend on colour

- **WHEN** a node outcome is rendered
- **THEN** its state is conveyed in text, and colour, where used, is redundant with
  that text

#### Scenario: Preview and result are comparable

- **WHEN** an operator previews an operation and then performs it
- **THEN** the result is presented in the same form as the preview

### Requirement: An unavailable capability is explained where the action would be

Where an operation is unavailable because the connection lacks a capability, the
control SHALL be present, disabled, and accompanied by the reason and the
configuration change that would enable it. The control SHALL NOT be hidden.

Where a capability is not yet known rather than known to be missing, the control
SHALL be enabled and the uncertainty stated, so that an operator is not blocked by
the absence of evidence.

The explanation SHALL be reachable by keyboard and SHALL NOT be available only on
hover.

#### Scenario: A missing capability explains itself in place

- **WHEN** a connection cannot perform an operation
- **THEN** the control is visible, disabled, and accompanied by the reason and the
  configuration that would enable it

#### Scenario: An unknown capability does not block the operator

- **WHEN** a capability has not yet been established
- **THEN** the control is enabled and the uncertainty is stated

#### Scenario: The explanation is reachable without a pointer

- **WHEN** a keyboard user reaches a disabled control
- **THEN** the reason is available to them without hovering

### Requirement: Forms label their fields, validate on blur, and never disable silently

Every input SHALL carry a visible label. A placeholder SHALL NOT be used as a
label.

A field whose value the system will reject SHALL be validated when the operator
leaves it, not only when the form is submitted, and its message SHALL appear beside
the field it belongs to.

A submit control SHALL NOT be disabled without the reason being visible. Either the
reason is shown, or the control stays enabled and validates on activation.

A field that cannot be changed SHALL be presented as immutable with the reason,
which is distinct from being disabled — one cannot be changed at all, the other is
unavailable right now.

Configuration that most operators will not change SHALL be collapsed behind a
disclosure rather than presented alongside the fields that identify the resource.

#### Scenario: Labels are visible

- **WHEN** a form is rendered
- **THEN** every input has a visible label and no field relies on its placeholder to
  name it

#### Scenario: A field is validated when it is left

- **WHEN** an operator enters a value the system will reject and moves to the next
  field
- **THEN** the message appears beside that field before the form is submitted

#### Scenario: A disabled submit explains itself

- **WHEN** a submit control is disabled
- **THEN** the reason is visible without the operator having to guess

#### Scenario: Immutable and unavailable are distinguishable

- **WHEN** a form shows a field that can never be changed and one that is currently
  unavailable
- **THEN** the two are presented differently, each with its reason

### Requirement: A view teaches what is missing when it has nothing to show

An empty view SHALL state what the resource is, why there is none, and the action
that creates one where the operator is permitted to take it. A view whose empty
state reads only that there is nothing SHALL NOT be shipped.

An empty result caused by a filter SHALL be distinguishable from one caused by there
being nothing at all, and SHALL offer to clear the filter.

An empty view that is empty because a node could not be reached SHALL say so rather
than presenting an absence as a fact.

#### Scenario: Nothing to show teaches what would be shown

- **WHEN** a view has no rows
- **THEN** it explains what the resource is and how one comes to exist

#### Scenario: Filtered-empty is not the same as empty

- **WHEN** a filter excludes every row
- **THEN** the view says so and offers to clear the filter

#### Scenario: Unreachable is not empty

- **WHEN** rows are absent because a node could not be reached
- **THEN** the view reports the unreachable node rather than showing an empty result

### Requirement: Destructive flows are operable and legible without a pointer

A dialog confirming a destructive action SHALL move focus into itself when it opens,
SHALL keep focus within itself while open, SHALL close on the escape key, and SHALL
return focus to the control that opened it when it closes.

Every control SHALL have an accessible name; an icon-only control SHALL carry one.
Focus SHALL remain visible throughout, and a focus indicator SHALL NOT be removed.

New views SHALL meet the contrast floor this product already applies to its text and
status colours, verified rather than assumed, and SHALL be checked in both colour
schemes.

Any motion introduced SHALL be suppressed when the operator has asked for reduced
motion.

#### Scenario: A confirmation dialog is keyboard-complete

- **WHEN** an operator opens and dismisses a destructive confirmation using only the
  keyboard
- **THEN** focus enters the dialog, stays within it, escape dismisses it, and focus
  returns to the control that opened it

#### Scenario: Both colour schemes are verified

- **WHEN** a new view is reviewed
- **THEN** its contrast is checked in the light and the dark scheme rather than
  inferred from one

#### Scenario: Reduced motion is honoured

- **WHEN** an operator has asked for reduced motion
- **THEN** motion introduced by these views does not play

### Requirement: New views reuse the product's existing view, token, and confirmation machinery

New tabular views SHALL use the product's existing virtualised table, with its
paging, sorting, sort-state announcement and node attribution, rather than a new
table implementation.

Destructive confirmations SHALL use the product's existing typed-confirmation
component rather than a further hand-rolled copy.

Colour and spacing SHALL come from the product's semantic token layer. A raw colour
literal SHALL NOT appear in a component.

Layout SHALL use logical properties, never physical ones.

State that describes what is being viewed — filters, selection, paging, the open
resource — SHALL live in the URL so a view can be shared and restored, while
transient interaction state stays local.

#### Scenario: A new table is not a new table implementation

- **WHEN** a new tabular view is added
- **THEN** it uses the existing virtualised table and inherits its behaviour

#### Scenario: A shared view reopens as it was left

- **WHEN** an operator shares the address of a filtered, sorted view
- **THEN** the recipient sees the same filter and sort
