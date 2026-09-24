# operator-ui Specification

## Purpose
TBD - created by archiving change 01-queue-and-address-lifecycle. Update Purpose after archive.

## Requirements

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

### Requirement: A view whose size grows with the cluster is bounded

The system SHALL bound what any view renders at once, so that the amount of work a
screen does is a function of what the operator can see rather than of how large the
cluster is. A view listing rows SHALL either present a bounded page of them with a
way to reach the rest, or render only the rows within the viewport, or both.

A view that is bounded SHALL make the bound visible: an operator SHALL NOT be left
unable to tell whether they are looking at everything.

#### Scenario: A large result does not render in full

- **WHEN** a view's underlying result contains far more rows than fit on a screen
- **THEN** the number of rows rendered is bounded, and the view states that more
  exist and how to reach them

#### Scenario: A single page is not silently the whole answer

- **WHEN** a view fetches a bounded page of results and more pages exist
- **THEN** the view offers a way to reach the following pages

#### Scenario: A dense graph degrades rather than drawing everything

- **WHEN** a topology contains more nodes than can be drawn legibly at once
- **THEN** the view reduces the detail it draws rather than drawing every node at
  full detail, and states that it has done so

### Requirement: A capture subscription's cost and state are visible where it is managed

The interface that lists capture subscriptions SHALL show, for each one: its per-node state
with the reason for any node that is not capturing, what it currently holds against both its
retention and its size bound, and its estimated loss.

A subscription that is degraded SHALL be distinguishable from one that is healthy in text,
not by colour alone.

#### Scenario: A degraded node is named with its reason

- **WHEN** a capture subscription is active on one node and refused on another
- **THEN** both are listed, and the refusal is named in text

### Requirement: Deleting a capture subscription states its full blast radius

Deleting a capture subscription destroys stored application payload and removes objects from
every live broker in the cluster. The interface SHALL state, before the action can be armed,
how many captured messages will be destroyed and which broker objects will be removed from
which nodes, and SHALL require the subscription's name to be typed.

#### Scenario: The blast radius is stated before the action is armed

- **WHEN** an operator begins deleting a capture subscription
- **THEN** the number of captured messages to be destroyed and the broker objects to be removed are stated, and the action cannot be armed until the subscription's name is typed

### Requirement: The console keeps its results reachable while it explains itself

The console's statements about a result — its source, its completeness, the bounds it
reached and the notices it carries — SHALL be presented so that they do not displace the
result itself out of view.

A screen that pushes its rows below the fold to explain them defeats the explanation: an
operator mid-incident scrolls past the text to reach the rows, which is exactly the text
they needed. The statements SHALL remain reachable and SHALL NOT be dismissable where the
underlying requirement forbids dismissal.

#### Scenario: A result carrying several statements still shows its rows

- **WHEN** a result carries a source statement, more than one bound and more than one notice
- **THEN** the rows remain visible without scrolling past the statements, and each statement remains reachable

### Requirement: A control disabled by capability or permission explains itself from the keyboard

Every control the console disables because a capability or a permission is missing SHALL
carry its reason on something that takes keyboard focus. A disabled control takes no focus,
so the explanation SHALL hang off an element that does.

#### Scenario: The reason for a disabled control is reachable by keyboard

- **WHEN** an operator using only the keyboard reaches a control the console has disabled
- **THEN** the reason it is disabled is reachable and readable without a pointer

### Requirement: A view that accumulates rows indefinitely states its bound

A view that receives rows continuously SHALL be bounded, and SHALL state when it has begun
discarding the oldest rows to stay within that bound.

#### Scenario: A long-running tail states that it is discarding

- **WHEN** a tail has delivered more rows than the view retains
- **THEN** the view states that the oldest rows are no longer shown

### Requirement: Navigation is grouped into a fixed, ordered set of groups

The navigation SHALL present every view within one of a fixed, ordered set of groups: Observe, Messaging, Resources, Configuration, Activity. Within a group, views SHALL appear in a declared order. When the navigation is expanded, each group SHALL show a visible heading exposed to assistive technology as a heading. When collapsed, groups SHALL be separated visually and SHALL keep their accessible names. The command palette SHALL list views under the same groups. A group with no enabled views SHALL NOT be shown.

#### Scenario: Views appear under their group headings

- **WHEN** an operator opens a cluster with the navigation expanded
- **THEN** each view is listed under its group's heading, groups appear in the fixed order, and a screen reader announces each heading

#### Scenario: Collapsed navigation keeps groups distinguishable

- **WHEN** the navigation is collapsed
- **THEN** groups remain visually separated and each view keeps its accessible name

#### Scenario: A group whose views are all disabled is not shown

- **WHEN** every view in a group belongs to disabled features
- **THEN** that group's heading does not appear

### Requirement: A disabled feature is absent from navigation and explains itself at its address

A view belonging to a feature disabled on this installation SHALL NOT appear in navigation, the command palette, or any screen section that feature would contribute. Navigating directly to such a view's address SHALL show a page that:
- states the feature is disabled on this installation;
- names the startup property that enables it;
- offers a way back to the cluster.

The page SHALL NOT be blank and SHALL NOT look like a generic not-found page.

A view belonging to an enabled feature that the operator lacks permission for SHALL remain visible and disabled, with the reason available by keyboard.

#### Scenario: A deep link to a disabled feature explains how to enable it

- **WHEN** an operator opens the address of a view whose feature is disabled
- **THEN** the page states the feature is disabled on this installation, names `artemis-studio.features.<id>.enabled`, and links back to the cluster

#### Scenario: A disabled feature contributes nothing to shared screens

- **WHEN** a feature that contributes a section to the settings screen is disabled
- **THEN** the settings screen renders without that section and without an empty placeholder for it

#### Scenario: A permission gap is not treated as a disabled feature

- **WHEN** an operator lacks the read permission for an enabled feature's view
- **THEN** the navigation entry is visible and disabled, and its reason is reachable by keyboard

### Requirement: Governed message content is presented for what it is

Wherever message content is shown (message detail, SQL results, flow detail), the interface SHALL present each governed value distinctly:

- **masked value**: a labelled token naming its class in words;
- **dropped credential**: a labelled marker;
- **withheld content**: a notice stating why, and naming the setting that changes it where one does;
- **value shown in clear by grant**: marked as sensitive.

Colour SHALL NOT be the only carrier of any of these states. Copying or downloading governed content SHALL state that the copy contains masked values where it does.

#### Scenario: A masked value is labelled in words

- **WHEN** a masked email appears in message detail
- **THEN** it is shown as a token reading that it is a masked email, not as a string resembling an address

#### Scenario: A withheld body names its reason

- **WHEN** message detail shows a message whose binary body was withheld
- **THEN** the body area states that the binary body could not be classified and was withheld

### Requirement: The governance screens teach and gate honestly

The governance screens SHALL:

- list rules, marking built-in rules as not deletable while leaving their enable control available;
- list open findings with confirm and dismiss actions;
- show how many stored rows are still under an earlier policy version.

A user without the governance write permission SHALL see the change controls disabled, with the reason reachable from the keyboard. An empty inbox SHALL explain what a finding is and why there are none; a filtered-empty inbox SHALL say so and offer to clear the filter.

#### Scenario: A read-only user sees why controls are disabled

- **WHEN** a user with governance read but not governance write opens the rules screen
- **THEN** the create, edit and delete controls are disabled and a keyboard-reachable explanation names the missing permission

#### Scenario: An empty inbox teaches

- **WHEN** the inbox has no findings
- **THEN** it explains that findings are personal data detected in fields no rule covers, and that none have been detected yet

### Requirement: A previewed mutation is exactly what is submitted

Where a mutation is previewed before it is confirmed, as when creating a divert, the values
previewed SHALL be exactly the values submitted. While a preview is shown its inputs SHALL
NOT be editable. Changing a value SHALL require leaving the preview, and the preview SHALL
be run again before the mutation can be confirmed.

The form SHALL validate each field on blur against the same rules the server enforces, and
SHALL show any per-field error the server returns beside that field. On a rejected
submission, focus SHALL move to the first invalid field. A control that cannot yet be used
SHALL state why.

The dialog SHALL NOT be dismissible while its mutation is in flight. The outcome — succeeded,
failed, or partial with per-node detail — SHALL be shown once the mutation settles.

#### Scenario: Editing after preview requires a new preview

- **WHEN** an operator previews a divert and then chooses to change its forwarding address
- **THEN** the preview is left, the fields become editable, and the creation cannot be confirmed until a new preview has run

#### Scenario: A server field error is shown beside its field

- **WHEN** the server rejects a divert because its name contains an unsafe character
- **THEN** the error is shown beside the name field and focus moves to it

#### Scenario: The outcome of an in-flight mutation is not lost

- **WHEN** an operator tries to close the divert dialog while its creation is in flight
- **THEN** the dialog stays open, and shows the per-node outcome when the creation settles

### Requirement: Arming a capture subscription is confirmed against its dry run

The interface SHALL render the dry run before a capture subscription can be armed. The dry
run shows:
- the resolved addresses;
- the target nodes;
- the capture queue's bounds in messages and bytes;
- the broker objects to be created;
- the equivalent broker configuration.

Arming a capture subscription SHALL require typing the pattern. Its bounds — message and
byte limits, per-message body limit, ingest rate and filter — SHALL be editable behind a
disclosure, validated on blur against the server's limits. A value outside those limits
SHALL be refused with its reason, never silently adjusted.

The copy SHALL match the subscription's mode: a captured subscription SHALL NOT be described
as sampled, and a sampled one SHALL NOT be described as capturing everything.

#### Scenario: Capture cannot be armed without its dry run and typed pattern

- **WHEN** an operator starts creating a capture subscription
- **THEN** the dry run is shown and the action cannot be armed until the pattern has been typed

#### Scenario: An out-of-range bound is refused, not clamped

- **WHEN** an operator enters a capture queue bound above the server's maximum
- **THEN** the field shows the maximum and the reason, and the value is not silently changed

#### Scenario: Copy follows the mode

- **WHEN** an operator views a subscription in capture mode
- **THEN** no text on it describes the subscription as sampled

### Requirement: Per-node outcomes and errors are announced

A per-node outcome summary SHALL be announced to assistive technology when it appears or
changes, and an error that stops an action SHALL be announced as an alert. This applies to
the preview and to the result.

#### Scenario: A partial outcome is announced

- **WHEN** a divert creation settles with a partial outcome
- **THEN** the per-node summary is announced by assistive technology without the operator moving focus to it

### Requirement: An unavailable figure is stated, never shown as zero

Where a count, total or estimate that a view would show is unavailable, the view SHALL state
that it is unavailable and why. This covers a browse total, a capture loss estimate, and a
capture footprint. It SHALL NOT show zero, a dash that reads as zero, or omit the figure.

#### Scenario: A browse without a total says so

- **WHEN** a browse response states that its total is unavailable
- **THEN** the message view says the total is unavailable and why, and shows no number in its place

#### Scenario: A filtered capture's loss says it cannot be estimated

- **WHEN** a capture subscription's loss estimate is unavailable
- **THEN** the subscription view says the estimate is unavailable and why

### Requirement: A capture deletion states what happens on unreachable nodes

Before a capture subscription's deletion can be armed, the interface SHALL state that broker
objects are removed from reachable nodes immediately, and from unreachable nodes on their
next reconciliation. After the deletion, it SHALL show the per-node removal outcome.

#### Scenario: An unreachable node's cleanup is stated

- **WHEN** an operator deletes a capture subscription while one of its nodes is unreachable
- **THEN** the confirmation states that a node that does not answer is cleaned on its next reconciliation, rather than claiming every node was cleaned

### Requirement: Sampling is named as sampling where capture would see more

Wherever a sampled subscription is chosen or listed, the UI SHALL say briefly that it is only
sampling and that a message consumed between two polls is not recorded.

Where request-reply tracing depends on addresses that no enabled capture subscription covers,
the UI SHALL show a short hint naming those addresses and where capture is turned on. The hint
SHALL disappear once every such address is captured.

#### Scenario: A traced address that is only sampled gets a hint

- **WHEN** an operator traces a request address and a reply address, and neither is captured
- **THEN** the tracing screen names both as only sampled and points to where capture is turned on

#### Scenario: The hint goes away once capture covers the addresses

- **WHEN** an enabled capture-everything subscription covers every traced address
- **THEN** no capture hint is shown

#### Scenario: A sampled subscription says it is only sampling

- **WHEN** an operator views or creates a sampled subscription
- **THEN** it is described as just sampling, which misses messages consumed between polls

### Requirement: Content that moves on its own can be paused and honours reduced motion

A view that animates without operator input for longer than five seconds SHALL offer a
visible, keyboard-reachable control that pauses the animation, SHALL stop the animation
while the operator's system requests reduced motion, and SHALL stop it while the view is not
visible. Whatever the animation conveys SHALL also be conveyed without motion, so pausing
removes no information. Pausing an animation SHALL NOT pause data refresh; pausing data
refresh remains its own control.

#### Scenario: Pause keeps information

- **WHEN** an operator pauses an animated view
- **THEN** the movement stops and every value it conveyed is still shown as text or shape

#### Scenario: Reduced motion is honoured

- **WHEN** the operator's system requests reduced motion
- **THEN** the view opens without movement and offers no way for it to start on its own

#### Scenario: Hidden view stops moving

- **WHEN** the browser tab showing an animated view is hidden
- **THEN** the animation stops and resumes, unless paused, when the tab is shown again

### Requirement: A health verdict is carried in words, with colour as redundant emphasis

A view presenting a derived verdict SHALL state it as a word. Colour SHALL NOT be the only
carrier of the verdict, and SHALL be applied only where something is wrong, so a healthy
view stays near-monochrome.

#### Scenario: A verdict is legible without colour perception

- **WHEN** a ranked health view is rendered
- **THEN** each row's verdict is readable as text, and removing colour removes no
  information

#### Scenario: A healthy cluster is quiet

- **WHEN** every queue in a cluster is healthy
- **THEN** the view carries no warning or danger colour

#### Scenario: An uncomputed verdict is distinguishable from a healthy one

- **WHEN** a queue's verdict could not be computed
- **THEN** the row states that, and is visually distinct from a row reporting health

### Requirement: A graph that can be edited is operable without a pointer and has a non-graph equivalent

A view that presents a structure as a graph and allows that structure to be edited SHALL be
fully operable from the keyboard: focus SHALL be able to enter the graph, move between its
elements, open the element that has focus, and leave the graph again, and every element SHALL
carry an accessible name that says what it is and what it connects.

Such a view SHALL NOT be the only way to reach what it presents. Every element it shows and
every edit it offers SHALL also be reachable from a non-graph presentation of the same
underlying state, so that an operator who cannot use the graph is not locked out of the
capability.

A graph that does not animate on its own needs no pause control; a graph that does SHALL meet
the same pausing, reduced-motion and off-screen requirements as any other moving content.

#### Scenario: The graph is traversed without a pointer

- **WHEN** an operator moves focus into an editable graph and uses the keyboard alone
- **THEN** focus moves between its elements, the focused element can be opened, and focus can
  leave the graph again

#### Scenario: Every element carries a name

- **WHEN** an element of the graph receives focus
- **THEN** its accessible name states what the element is and what it connects

#### Scenario: The graph is not the only path

- **WHEN** an operator uses the non-graph presentation of the same state
- **THEN** every element the graph shows and every edit it offers is available there

### Requirement: An authored change that has not been applied is stated as such

Where a view lets an operator author a change that is recorded before it takes effect, the
view SHALL state, in words, which parts of what it shows have been authored and not yet
applied. Colour SHALL NOT be the only carrier of that distinction.

An authoring surface SHALL NOT be presented as a preview of a mutation. The preview remains
the confirmation step, whose inputs do not change while it is shown and which is recomputed
when the underlying state has moved.

#### Scenario: Unapplied authoring is legible without colour

- **WHEN** a view shows both applied and authored-but-unapplied elements
- **THEN** each authored-but-unapplied element says so in text, and removing colour removes no
  information

#### Scenario: Authoring does not stand in for the preview

- **WHEN** an operator has authored a change and moves to apply it
- **THEN** a preview is computed and confirmed separately, and its inputs cannot be edited
  while it is shown

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

### Requirement: Settings is presented as grouped tabs

The Settings page SHALL present each settings section as a tab in a vertical tab list, grouped under fixed headings in this order: the operator's own preferences, Studio-wide configuration, this cluster, and plugins. The open tab SHALL be held in the address so it can be shared and restored. The tab list SHALL be operable from the keyboard, and changing tab SHALL move focus to the opened section's heading. A section contributed by a plugin SHALL appear under the plugins heading.

#### Scenario: The open tab survives a reload

- **WHEN** an operator opens the Broker credentials tab and reloads the page
- **THEN** the Broker credentials tab is open

#### Scenario: The tabs are keyboard operable

- **WHEN** focus is on the tab list and the operator presses the down arrow and Enter
- **THEN** the next tab opens and focus moves to its heading

### Requirement: Plugin management states consequences before acting and outcomes after

The plugin management screen SHALL state, before any plugin action is confirmed, what it will do, to which plugin, and for how long anything will be unavailable. It SHALL render the pending, succeeded, failed and partially-succeeded outcomes of every action, announce each outcome through a live region, and pair every failed, incompatible or restart-required state with the action that resolves it. Plugin state SHALL be carried in words, with colour only as redundant emphasis where something is wrong. Closing the screen during an activation SHALL NOT cancel it, and its progress SHALL remain visible from the application header.

#### Scenario: A failed plugin shows its remedy in place

- **WHEN** a plugin failed to activate
- **THEN** its row states the cause in words and offers the action that resolves it

#### Scenario: Leaving during activation does not cancel it

- **WHEN** an operator closes the activation dialog while the activation is in progress
- **THEN** the activation continues and its progress is visible from the header
