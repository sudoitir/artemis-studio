## ADDED Requirements

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
