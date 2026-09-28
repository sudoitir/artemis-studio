## ADDED Requirements

### Requirement: The SDK offers a read-only diagram

The plugin SDK SHALL export a read-only diagram component that lays out given nodes and edges automatically (layered, off the main thread) and renders them in Studio's theme in both colour schemes. It SHALL NOT allow dragging, connecting or deleting. Nodes SHALL be focusable and selectable by keyboard, and a node's accessible name SHALL include its kind, label and any stated problem. A node with a problem SHALL show it in words as well as colour. A change that keeps the same nodes and edges SHALL NOT move anything. A layout failure SHALL be stated in place.

#### Scenario: Keyboard selection
- **WHEN** a user tabs to a node and presses Enter
- **THEN** the component reports that node as selected, and the selection is visible and announced

#### Scenario: A problem is stated in words
- **WHEN** a node is given a problem with a reason
- **THEN** the node shows the word "Invalid" and the reason is part of its accessible name
