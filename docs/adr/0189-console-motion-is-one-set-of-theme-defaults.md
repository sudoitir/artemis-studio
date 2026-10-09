# ADR-0189: Console motion is one set of theme defaults, and the colour scheme is chosen in the user menu

- **Status**: accepted
- **Date**: 2026-10-09
- **Deciders**: Mahdi Amirabdollahi

## Context

The motion tokens (`--as-duration-fast/base/slow`, 120/180/240 ms, and `--as-ease`) were used by Studio's
own CSS but by none of Mantine's components. Every dialog, drawer, menu, popover, tooltip, collapse and
progress bar ran Mantine's defaults: plain `ease`, the same speed in and out, a 30 px drop on modals, menus
that faded from no origin, and toasts with an overshooting curve. The command palette and row menus, opened
from the keyboard many times a day, animated on every open. Progress bars kept moving under reduced motion.

The header also carried a single icon that cycled system, light and dark (ADR-0159), a rarely used three-way
choice behind one glyph, beside controls an operator uses every minute. Flow animated up to 400 dots
(ADR-0080, ADR-0095), each on the main thread, while the operator panned.

## Decision

1. **Motion is set once, in `theme.ts`, from `other.motion`.** Modals scale from 0.96 with opacity (base in,
   fast out), drawers slide (slow in, base out), menus and popovers scale from 0.96 out of the edge that
   faces their trigger (`transform-origin` from `data-position`), tooltips fade on the fast token after a
   300 ms delay, collapses take the base token, and progress bars the slow one. Exits are faster than
   entrances; everything uses `--as-ease`. A component sets its own transition only to differ.
2. **What the keyboard opens appears at once.** The command palette, the shortcuts help and a row's
   context menu have no entrance animation; the sidebar collapses without animating the page's width.
3. **Press feedback is a 0.97 scale** on Mantine's pressable controls and on Studio's own rows and triggers;
   hover styles apply only under `(hover: hover) and (pointer: fine)`.
4. **Toasts** sit below the header, at most three, without Mantine's overshoot.
5. **Flow animates at most 150 dots** (amends ADR-0080 and ADR-0095), busiest edges first as before, and
   pauses them while the operator pans or zooms.
6. **The colour scheme is chosen in the user menu** as System, Light or Dark (amends ADR-0159's "one
   control, one action"). The palette keeps its cycling action; `boot-prefs.js` and the stored value are
   unchanged.

## Consequences

- A new Mantine component moves like the rest without review, and reduced motion still stops it, through
  `respectReducedMotion` and the zeroed duration tokens; progress honours it through the same token.
- Tests that read a dialog right after opening it must wait for it (`findBy…`), as Mantine now mounts it
  on the next frame.
- The header has one control height and one less icon; changing the scheme takes two clicks instead of one
  to three.
- A dense Flow graph shows fewer moving dots, and none while it is being moved.

## Alternatives considered

- **Per-component transitions.** Each screen chose its own and none matched; this is what we had.
- **No motion at all.** Clear for the keyboard surfaces, which now have none, but dialogs and menus lose the
  spatial cue that tells the operator where they came from.
- **Keep the header toggle.** Fast to reach, but it spends header room every page pays for on a choice made
  once.
