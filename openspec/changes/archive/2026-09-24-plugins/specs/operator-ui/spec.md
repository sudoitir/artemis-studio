## ADDED Requirements

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
