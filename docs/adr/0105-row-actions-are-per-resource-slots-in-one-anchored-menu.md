# ADR-0105: Row actions are per-resource slots in one anchored menu, served by a host that outlives the row

- **Status**: accepted
- **Date**: 2026-09-24
- **Deciders**: Artemis Studio maintainers

## Context

Operators act on one resource at a time far more often than on many. Today the actions for
a queue live in its detail drawer. The actions for a connection, session, consumer, address
or divert live in a trailing grid cell that also mounts the confirmation dialog. Three
problems follow:

- **A pointer is needed.** `VirtualTable` rows cannot be focused or activated from the
  keyboard, so Pause or Delete on a queue is out of reach without a mouse.
- **The outcome can be lost.** A dialog mounted inside a virtualized cell is unmounted
  when its row leaves the window. A close that succeeds refetches the listing, the row
  goes, and the dialog goes with it: the four-outcome result disappears and focus falls to
  `<body>`.
- **Features cannot reach each other's rows.** A queue row could usefully offer "Browse
  messages" (messages), "Purge…" (bulk), "Transfer…" (transfer), "Show in Flow" (flow) and
  "Open history" (metrics). The frontend boundaries (ADR-0074) forbid queues importing any
  of them.

ADR-0070 says a need the contract does not cover is met by a new contribution type or a
new slot.

## Decision

1. **Row actions are kernel slots, one per resource kind**: `queue.actions`,
   `address.actions`, `consumer.actions`, `session.actions`, `connection.actions`,
   `producer.actions`, `message.actions` and `divert.actions`.
   - Each receives `{ clusterId, target, host, mode }`.
   - `target` is the resource's identity, plus an optional snapshot of the generated DTO
     the row was drawn from.
   - An item needing a figure the target lacks looks it up, and is offered while that
     lookup loads: unknown is not unavailable.
   - A contribution names one of a closed, ordered set of **sections**: `open`, `copy`,
     `operate`, `destroy`. The menu separates the sections, and adding one is a kernel
     change.
2. **Links are slots too**: `queue.link`, `address.link`, `session.link` and
   `connection.link`.
   - A resource name rendered by another feature becomes an anchor through the owning
     feature's contribution.
   - When that feature is disabled, the name is plain text, never a dead link.
   - Plugins may not contribute to link slots. A plugin that could redirect every queue
     link would be a phishing surface inside the console.
3. **One menu per grid, anchored**, not one per row.
   - A controlled Mantine `Menu` is anchored to a portalled, zero-size, fixed-position
     element. For the pointer, the element is moved to the cursor; for the keyboard, to the
     row's Actions trigger.
   - The items of a closed menu are not mounted. A 200-row page costs one menu, not 200.
   - Right-click opens it. The native menu is kept on anchors, inputs, with Shift held, or
     when text is selected, so copying out of the grid still works.
   - Shift+F10 and the ContextMenu key open it at the trigger. The trigger is a visible
     button per row.
   - `Menu.ContextMenu` is not used:
     - it offers no keyboard trigger;
     - a keyboard-originated `contextmenu` event would open it at (0, 0);
     - it always prevents the native menu;
     - it forces `user-select: none` on what it wraps.
4. **A blocked item stays focusable.** Mantine skips `disabled` items in keyboard
   navigation, which would hide the reason from a keyboard user (non-negotiable #5).
   - A blocked item is `aria-disabled`, shows its short reason, and is described by it.
   - Activating it opens the full explanation, including the `broker.xml` snippet.
5. **Dialogs live in an action host**, mounted once per cluster layout, never in a row.
   - `host.open(Dialog, props)` mounts the dialog closed and then opens it, so the
     `onEnterTransitionEnd` hook that takes each dry-run preview still fires.
   - On close it returns focus to what opened the menu. If that row is gone, focus goes to
     the grid. If the location changed, focus is left alone.
   - `host.explain` shows a blocked item's reason. `host.announce` speaks an outcome, such
     as a copy, through a polite live region.
6. **Flow renders menus in `navigate` mode.** The flow view issues no mutating request.
   Its menus show the `open` and `copy` sections and one "Act on this in …" link to the
   screen that performs the action with its confirmation.
7. **Plugin contributions** to action slots are namespaced `<id>.…`, must name a section,
   and are wrapped so a throw stays their own.

## Consequences

- A queue row offers the verbs of five features without any of them importing another.
  Each feature owns its items, its gate and its dialog.
- The outcome of an action outlives its row, which fixes the lost-outcome bug on the
  resource grids.
- The kernel gains a small host and a renderer, and `SlotContribution` gains an optional
  `section`. The contract stays at version 1, because every change is additive.
- Every item has to be written as a component. That is more ceremony than a data
  descriptor, but it lets an item use the same hooks (`useCan`, `useCluster`, `gateFor`) as
  the button it mirrors.

## Alternatives considered

- **A new `actions` field on `StudioFeature` with data descriptors.** It would re-implement
  what slots already give: enabled-feature filtering, ordering, plugin namespacing and
  guarding. And gating needs hooks that a descriptor cannot call.
- **One `Menu` per row.** It is simple, but it multiplies popover machinery by the rendered
  row count. It also still mounts dialogs in cells.
- **Keeping actions in drawers only.** An operator would still have to open a panel to
  reach a verb, and the keyboard path would still not exist.
