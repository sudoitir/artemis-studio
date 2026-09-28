# ADR-0117: The SDK exposes a read-only diagram

- **Status**: accepted; its "read-only" point is amended by [ADR-0120](0120-the-sdk-diagram-offers-caller-defined-insertions-and-actions.md)
- **Date**: 2026-09-28
- **Deciders**: Artemis Studio maintainers

## Context

Studio draws three graphs: topology, message flow and routing. All three use `@xyflow/react` to
render and ELK (`ui/graph/elk.ts`, in a worker, ADR-0080) to lay out. A plugin's UI may import only
the SDK, Mantine, React and TanStack (ADR-0100). A plugin that needs to show a structure as boxes and
arrows, such as the steps of a workflow, therefore has two choices: bundle its own graph library,
which means a second copy with its own theme and no layout worker, or show a list instead.

## Decision

The SDK exports `DiagramView`, a generic read-only diagram built on the same xyflow and ELK runner.

- **Input.** Plain data: nodes (`id`, `label`, optional `kind`, `detail`, `state`, `reason`) and
  edges (`source`, `target`, optional `label`, `dashed`), plus `selectedId` and `onSelect`.
  xyflow types never cross the SDK boundary, so the library can change under it.
- **Read-only.** Nothing drags, connects or deletes. Pan, zoom and "fit to view" are the only
  view controls.
- **Layout.** ELK layered, `DOWN` or `RIGHT`. It runs again only when the node ids or edges change,
  never when a label or state does, so nothing moves under the pointer.
- **Keyboard.** The diagram is one tab stop. Arrow keys move in reading order, Enter or Space
  selects, and the selection is announced.
- **Words carry meaning.** A node with a problem shows "Invalid" or "Warning" on the card, and has
  a thicker outline in the danger or warning token. Its accessible name is its kind, label and
  detail, then the labels of the edges into it, then the problem and its reason. Edge labels are
  drawn as chips and hidden from assistive technology, because their text is already in the
  target's name.
- **Theme.** The routing canvas's card and line styles, from `--as-*` tokens only, so both colour
  schemes follow. Transitions stop under reduced motion.
- **Failure.** A layout that fails is stated in place: "The diagram could not be laid out".

## Consequences

- Plugins get a diagram in Studio's theme without bundling a graph library.
- The component is public API. Its props are plain data and optional beyond `nodes`, `edges` and
  `aria-label`, so it can grow without breaking existing callers.
- Studio's own canvases stay as they are. They edit, animate or overlay live state, which this
  component deliberately does not.
