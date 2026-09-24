---
title: Keyboard shortcuts
description: Move between views, search, and act on any row of any grid without a pointer, and turn single-key shortcuts off if they get in your way.
---

# Keyboard shortcuts

Everything in Studio can be reached from the keyboard. Press **?** anywhere, or the
keyboard button in the header, to list the shortcuts. The list is built from the views that are enabled, so it is always current.

## Everywhere

| Keys | Does |
|---|---|
| **⌘ K** / **Ctrl K** | Open search: views, clusters, recent pages, queues as you type, and searches of connections, consumers and the rest |
| **⌘ B** / **Ctrl B** | Collapse or expand the sidebar |
| **?** | List the shortcuts |
| **/** | Focus the current view's filter |
| **g** then a letter | Go to a view of the open cluster |

The **g** letters:

| Letter | View | Letter | View |
|---|---|---|---|
| t | Topology | a | Addresses |
| f | Flow | c | Consumers |
| h | Consumer health | n | Connections |
| m | Metrics | p | Producers |
| l | Alerts | o | Routing |
| r | Requests | k | Configuration |
| q | Queues | e | Events |
| d | DLQ | b | Bulk runs |
| x | Transfers | u | Audit |
| s | SQL Console | i | Sessions |

A view whose feature is off has no letter. Plugins cannot claim letters.

## Grids

Each grid is one stop in the tab order. Once inside, the keys work like a spreadsheet:

| Keys | Does |
|---|---|
| **↑ ↓ ← →** | Move between cells, header included |
| **Home** / **End** | First or last cell of the row |
| **Ctrl Home** / **Ctrl End** | First or last row |
| **Enter** | Open the row |
| **Space** | Select the row, where rows can be selected |
| **Shift F10** or the menu key | Open the row's actions |
| **Ctrl C** / **⌘ C** | Copy the focused cell's full value, even when it is cut off |

A row's actions menu is the same one right-click and the row's **Actions** button open.
Items are grouped as Open, Copy, Operate and Destroy. An item you cannot use stays in the
menu with the reason. Select it and Studio explains why, with the `broker.xml` that
would enable it. A destructive item opens its usual preview and typed confirmation.

## Flow

Graph nodes take **Enter** to open their details, **Escape** to close them, and
**Shift F10** or the menu key for their menu. In the Split layout, focus the separator
between the graph and the pane: the **arrow keys** resize the pane and **Enter** folds
it away or brings it back.

## When single keys get in the way

Screen-reader users and people using speech input may trigger single-key shortcuts by
accident. Turn them off under **Settings → Display → Single-key shortcuts**, or from
the switch in the shortcuts list. That switch covers **g**, **?** and **/**. The modifier
shortcuts stay on, and the list can still be opened from the header's keyboard button.

Shortcuts never fire while you type in a field, inside a dialog or an open menu, or while
an input method is composing. On a non-Latin keyboard layout, the letters follow the
physical keys, so **g** then **q** still reaches Queues.

The design is recorded in
[ADR-0108](/reference/adr/0108-the-data-grid-is-one-tab-stop-with-roving-cell-focus) and
[ADR-0109](/reference/adr/0109-navigation-aids-and-single-key-shortcuts).
