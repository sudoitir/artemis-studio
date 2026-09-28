## MODIFIED Requirements

### Requirement: The SDK offers a read-only diagram

The plugin SDK SHALL export a diagram component that lays out given nodes and edges automatically (layered, off the main thread) and renders them in Studio's theme in both colour schemes. The component SHALL NOT change the diagram itself: it SHALL NOT allow dragging, connecting or deleting, and it reports every choice to its caller. Nodes SHALL be focusable and selectable by keyboard, and a node's accessible name SHALL include its kind, label and any stated problem. A node with a problem SHALL show it in words as well as colour. A change that keeps the same nodes and edges SHALL NOT move anything. A layout failure SHALL be stated in place.

The component SHALL offer zoom in, zoom out and fit controls with accessible names, and SHALL be able to fill the height of its container.

When the caller gives a list of things that can be inserted, each edge the caller marks as insertable SHALL show an insert control that offers that list and reports the edge and the choice, and Insert pressed on a focused node SHALL offer the same choices for the insertable edges into it. When the caller gives actions for nodes, each node SHALL offer them in a menu that opens from a control on the node, from the pointer's secondary button, and from the keyboard (Shift+F10 or the context-menu key) on the focused node, and SHALL report the node and the action. A disabled action SHALL be shown disabled with its reason, not hidden. Without either, the component SHALL show no editing controls.

#### Scenario: Keyboard selection
- **WHEN** a user tabs to a node and presses Enter
- **THEN** the component reports that node as selected, and the selection is visible and announced

#### Scenario: A problem is stated in words
- **WHEN** a node is given a problem with a reason
- **THEN** the node shows the word "Invalid" and the reason is part of its accessible name

#### Scenario: Inserting on an edge
- **WHEN** a caller marks an edge insertable, gives the insert choices "A" and "B", and a user activates that edge's insert control and chooses "B"
- **THEN** the component reports that edge and "B", and changes nothing itself

#### Scenario: Inserting from the keyboard
- **WHEN** an edge into a node is insertable and a user focuses that node and presses Insert
- **THEN** the insert choices are offered, and a choice reports that edge

#### Scenario: A node's actions from the keyboard
- **WHEN** a caller gives a node the actions "Move up" (disabled) and "Remove", and a user focuses that node and presses Shift+F10
- **THEN** a menu opens with "Move up" disabled and "Remove" available, and choosing "Remove" reports that node and "Remove"

#### Scenario: Read-only by default
- **WHEN** a caller gives neither insert choices nor node actions
- **THEN** the diagram shows no insert controls and no action menus
