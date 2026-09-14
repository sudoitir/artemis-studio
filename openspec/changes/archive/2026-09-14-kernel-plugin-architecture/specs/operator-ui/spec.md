## ADDED Requirements

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
