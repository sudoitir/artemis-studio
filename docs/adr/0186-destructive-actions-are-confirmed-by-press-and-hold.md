# ADR-0186: Destructive actions are confirmed by press and hold

- **Status**: accepted
- **Date**: 2026-10-09
- **Deciders**: Mahdi Amirabdollahi
- **Supersedes in part**: [ADR-0022](0022-dry-run-estimate-and-server-enforced-bulk-cap.md) and
  [ADR-0163](0163-pages-are-built-from-shared-page-parts.md), where they require typing a name

## Context

Non-negotiable #2 asked the UI to confirm a purge or a delete by typing the resource's name. Typing
works as a guard for one resource, and it fails as a pattern: the name is often long, the operator
pastes it, the field is one more thing to find in a dialog, a long run ("delete 37 queues") has no
name to type, and a phrase typed on a keyboard is not something a touch or a switch user can do.
It also trained operators to treat the confirmation as a form. What the guard has to stop is a click
that was not meant: a double click, a key left down, a dialog that appeared under the pointer.

## Decision

1. **Every confirmation with a danger tone is held.** A button fills with red while it is held and acts
   only when full (`--as-hold-duration`, 1.5 s). A click, a double click or a press let go early does
   nothing; the fill drains and the hint says to keep holding. It is held with the mouse, a finger or a pen
   (Mantine's `useLongPress`) or with Space or Enter (the hook has no keyboard, so Studio handles it).
2. **The button names the action and its count** ("Delete 37 queues"), because the typed name no longer
   restates it. The dialog above it states the blast radius, as before.
3. **Focus starts on Cancel.** The held button is never the first thing a keyboard user lands on.
4. **One component.** `ConfirmDialog` takes no `typedName`; a danger-tone dialog renders `HoldToConfirm`,
   which is also exported from the plugin SDK. `ConfirmByTyping` is removed.
5. **The fill is legible at every point.** A second copy of the label in the fill's colours is revealed
   by a clip that opens from the inline start, so the text keeps 4.5:1 contrast on both halves, in both
   schemes, and the direction follows the document (right to left included). The hold's length is a
   function of the control, not decoration, so reduced motion leaves it alone.
6. A screen-reader user hears the button's name and its description ("Press and hold to confirm."), and
   the outcome ("Confirmed.", "Released too early.") is announced.

Dry run, the estimate and the server-enforced caps are unchanged.

## Consequences

- One rule and one control across about 35 confirmations; no per-site decision about what to type.
- A plugin that used `ConfirmByTyping` or `typedName` must change to the held control (a contract
  change, with `Contract.VERSION` 13).
- Tests hold the button instead of typing: the test setup shortens `--as-hold-duration`, and a helper
  presses, waits and releases.
- Holding is slower than a click by design. That is the point for an irreversible action, and a
  non-destructive confirmation keeps its single click.

## Alternatives considered

- **Keep typing the name.** Rejected for the reasons above.
- **A second click, a checkbox or a countdown.** Rejected, as before: each is a click that can be
  repeated without reading.
- **A hold with no keyboard path.** Rejected: the console is operated from the keyboard.
