# ADR-0120: The SDK diagram offers caller-defined insertions and actions

- **Status**: accepted
- **Date**: 2026-09-28
- **Deciders**: Mahdi Amirabdollahi

## Context

[ADR-0117](0117-the-sdk-exposes-a-read-only-diagram.md) gave plugins `DiagramView`, a read-only
diagram. A plugin that edits the structure it draws, such as the steps of a workflow, can then change
that structure only from a form beside the diagram: every insertion, move and removal is a trip away
from the picture of what is being changed. Its only view control was "fit", and its height was fixed in
pixels, so it could not fill a resizable panel.

## Decision

`DiagramView` stays a component that never changes the nodes itself. It gains optional, plain-data
hooks through which the caller offers changes and makes them:

- **Insert on an arrow.** An edge marked `insertable` shows a "+" beside its label when the caller
  passes `insertChoices` and `onInsert`. The "+" opens a menu of those choices, and a choice is
  reported as `(edgeId, value)`. From the keyboard, Insert (or +) on a box offers the choices of the
  insertable arrows into it, so the diagram stays one tab stop.
- **Actions on a box.** `nodeActions(node)` returns the box's actions, and `onNodeAction` receives
  `(nodeId, actionId)`. The menu opens from a "⋯" in the box's corner (shown on hover, focus and
  selection), from a right-click, and from Shift+F10 or the menu key, as the flow canvas's nodes do.
  An action that cannot be taken there is listed with its reason, never removed (ADR-0107).
- **View controls.** Zoom in, zoom out and fit, without animation.
- **Height.** `height` also takes a CSS length, so `'100%'` fills a sized parent.

The menus are the shared `AnchoredMenu` and `ActionMenuItem`. Only user pans close an open menu; the
fit that follows a new layout does not. Without the new props, the diagram looks and behaves as before.

## Consequences

- A plugin builds a structure editor on the diagram without a graph library of its own. It keeps
  every rule about what may go where: the diagram only reports choices.
- The props are optional and additive, so existing callers are unaffected and the plugin contract
  version does not change.
- One list of insert choices applies to the whole diagram. A caller that allows a choice on some arrows
  and not others refuses it in `onInsert` and says why. Per-arrow choices can be added later without
  breaking this API.
- The keyboard path to an insert is a key on a box (Insert), not a tab stop per arrow. That keeps the
  diagram one tab stop, and costs a key to learn, which the boxes announce with `aria-keyshortcuts`.
