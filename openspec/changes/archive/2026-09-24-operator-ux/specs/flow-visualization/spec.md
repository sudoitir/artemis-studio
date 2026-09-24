## ADDED Requirements

### Requirement: The view splits into the graph and a monitoring pane that follows the selection

The flow view SHALL offer a Split layout beside Graph and Table. The Split layout SHALL
place the graph beside a monitoring pane, divided by a separator that the operator can
resize and collapse with pointer and keyboard. The pane size SHALL be remembered in this
browser.

The selected client, address or queue SHALL be part of the view's address, in every
layout. Reloading or sharing the address SHALL restore the selection.

With nothing selected, the pane SHALL show each broker node's totals. With a selection, it
SHALL show that resource.

#### Scenario: Selection survives reload

- **WHEN** an operator selects queue `orders` in the Split layout and reloads the page
- **THEN** the Split layout opens with `orders` selected and its pane shown

#### Scenario: Resizing from the keyboard

- **WHEN** an operator focuses the separator and presses the arrow keys
- **THEN** the panes resize

### Requirement: A resource's traffic and backlog are broken down per broker node, and imbalance is stated in words

For a selected queue or address, the pane SHALL list every serving node with that node's
backlog, consumers, messages in and messages out. A node that did not answer SHALL be
listed as not answering, and its figures SHALL be stated as unknown, never zero.

The pane SHALL state in words, and not by colour alone:
- when one node holds most of the backlog;
- when a node holds messages and has no consumer while other nodes have consumers;
- when a node's share of messages in far exceeds its share of messages out.

When nothing is imbalanced, it SHALL say the resource is balanced across its nodes.

For a queue, the pane SHALL also show its per-node trends over a selectable range, one
chart per node on a shared scale, where the metrics feature is enabled. For a client, it
SHALL state that client history is not kept.

The breakdown SHALL be requested only by the Split layout.

#### Scenario: Stranded backlog

- **WHEN** queue `orders` holds 9,000 messages on `artemis-b`, which has no consumer, and
  its consumers are on `artemis-a`
- **THEN** the pane states that `artemis-b` holds 9,000 messages and has no consumer, and
  that the consumers are on `artemis-a`

#### Scenario: An unanswered node is not zero

- **WHEN** one of two nodes did not answer the latest sweep
- **THEN** its row states that it did not answer and its figures read unknown

### Requirement: Menus in the flow view navigate and never act

A node of the graph and a row of the table SHALL offer a menu, by right-click, by
Shift+F10 or the ContextMenu key, and by an Actions control where one is shown. The menu
SHALL offer:
- the resource's Open and Copy actions;
- one link to the screen where it can be acted on.

It SHALL NOT offer an action that changes the broker.

#### Scenario: Acting from flow

- **WHEN** an operator opens the menu of a queue node
- **THEN** it offers opening the queue and a link to act on it in Queues, and no delete

### Requirement: The inspector opens the exact resource

The inspector's links SHALL open the exact queue, address or connection they name, not a
list filtered by the client's label. The table's rows SHALL be activatable from the
keyboard, as the graph's nodes are.

#### Scenario: Opening a queue from the inspector

- **WHEN** an operator activates "Open in Queues" for queue `orders`
- **THEN** the Queues view opens with `orders` open
