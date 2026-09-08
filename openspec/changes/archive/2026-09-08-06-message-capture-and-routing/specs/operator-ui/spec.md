## ADDED Requirements

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
