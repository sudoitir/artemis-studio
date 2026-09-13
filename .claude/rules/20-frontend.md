# Rule: how the frontend behaves

The binding source is the `operator-ui` capability in `openspec/specs/`. This file
is the working summary — if the two ever disagree, the spec wins and this file is
the thing that is wrong.

Studio's UI is an operations console. The operator is usually tired, often on
call, and about to do something they cannot undo. Every rule below exists because
the alternative misleads someone in that state.

## Mutations

- **Render four outcomes, not one.** Pending, succeeded, failed, and
  partially-succeeded. A screen that implements only the happy path is not
  finished. Where an operation fans out across nodes, partial is the outcome that
  matters most and the one most likely to be skipped.
- **A destructive action states its blast radius before it can be armed** — the
  resource, the nodes, and how much data will be destroyed. Confirm by typing the
  resource's name, using `ui/ConfirmByTyping.tsx`. Never a checkbox, a second
  click, or a countdown.
- **An unavailable estimate is stated, never omitted.** An absent number reads as
  zero, which is the most dangerous possible misreading.
- **The initiating control is busy while in flight** and cannot be re-submitted.
- **Announce the outcome through an `aria-live` region.** An operator using a
  screen reader otherwise gets no signal that a destructive action completed.
- **A failure states its cause and the next action.** "Something went wrong" is not
  shippable copy.

## Capability and permission gating

- **Never hide a control to express that it is unavailable.** Visible, disabled,
  with the reason and — where one exists — the exact `broker.xml` that would enable
  it (non-negotiable #5). A silently missing button teaches the operator that the
  product cannot do something.
- **Unknown is not unavailable.** A capability that has not been established yet
  leaves the control *enabled* with the uncertainty stated. Blocking on the absence
  of evidence locks operators out of brokers that work fine. See ADR-0049 D5.
- **The explanation is keyboard-reachable**, never hover-only. A disabled control
  takes no focus, so the explanation hangs off something that does.
- **Gate on `kernel/auth/useCan.ts`**, never a hand-rolled grant check, and only ever to
  disable-with-a-reason. The server is the enforcement point.
- **While grants are still loading, offer the control.** Rendering "you do not have
  permission" before the answer arrives is a claim that has not been checked.

## Forms

- Every input has a **visible label**. A placeholder is not a label.
- **Validate on blur**, with the message beside its field — not only on submit.
- **Never disable submit silently.** Either show the reason, or keep it enabled and
  validate on activation.
- **Immutable is not disabled.** A field that can never change is presented as a
  fact about the resource; one that is merely unavailable right now is a disabled
  input. An operator who confuses them goes looking for the way to enable it.
- Configuration most operators will not touch goes **behind a disclosure**; the
  fields that identify the resource come first.
- **Autofocus the first invalid field** on a rejected submit.

## Empty and error states

- An empty view **teaches**: what the resource is, why there is none, and the
  action that creates one where the operator may take it.
- **Filtered-empty is not empty.** Say so, and offer to clear the filter.
- **Unreachable is not empty.** A view missing rows because a node did not answer
  says that, rather than presenting an absence as a fact.

## Presentation

- **Colour is never the only carrier of meaning.** State goes in words; colour is
  redundant emphasis, used only where something is wrong. A healthy view is
  near-monochrome.
- **Verify contrast, in both schemes.** The AA floor is 4.5:1 for body text.
  Measure it — do not infer the light scheme from the dark one. Mantine's yellow
  and orange ramps do not reach 4.5:1 on white at *any* step, so a light-scheme
  warning colour needs a dark amber literal.
- **Tabular figures on numeric columns**, so a difference between rows is visible
  without reading digits.
- **Semantic tokens only.** `--as-*` from `theme.css`; never a raw colour literal
  in a component. A new token only when no existing one fits.
- **Logical properties** (`inline-start`, `block-end`), never `left`/`right`.
- **Honour reduced motion.**

## Reuse

- New tabular views use the existing virtualised table, with its paging, sorting,
  sort announcement and node attribution.
- Destructive confirmations use `ui/ConfirmByTyping.tsx`.
- A per-node result uses `ui/NodeOutcomeSummary.tsx`, for the preview *and* the
  result, so what was confirmed and what happened are comparable.
- DTOs come from `kernel/api/schema.d.ts`, generated. Never hand-written; a feature
  names the ones it uses in its `api.ts`.

## Where code goes

The binding decisions are ADR-0070 and ADR-0074; `docs/architecture.md` has the map.

- **A screen belongs to a feature**: `src/features/<id>/`, named after its backend
  module. Its `feature.ts` calls `defineFeature` with its routes, navigation, palette
  groups, stream topic handlers and slot contributions, and `src/app/features.ts` lists
  it. Its hooks and query keys are in its own `api.ts`.
- **The kernel never imports a feature.** A shared screen shows another feature's
  panel through a kernel-owned slot (`kernel/slots.ts`); a new slot is a kernel change
  with a consumer, not a speculative one.
- **Another feature is reached only through its `index.ts`**, and only along an edge
  listed in `web/eslint.config.js`. Prefer a slot to a new edge.
- **Navigation** names one of the kernel's groups (`kernel/nav/groups.ts`). Adding a
  group is a kernel change.
- **Shared presentational components** are in `src/ui/`, which imports nothing of the
  app but generated DTO types.
- A feature's view is wrapped in `featureView`, so a disabled feature's address
  explains itself instead of failing.

## State ownership

Server state via TanStack Query; whatever describes what is being viewed —
filters, sort, paging, the open resource — lives in the URL so a view can be
shared and restored; transient interaction state stays local. No global store for
what a URL can hold.

## Tests

Query **by role and accessible name**, never by class or test id — the harness in
`src/test/render.tsx` is set up for it. `renderAppAt(path)` renders the composed app
over an in-memory history when the real router is what is under test. A destructive flow gets an asserted
keyboard-only pass: focus enters the dialog, escape dismisses it, focus returns to
the trigger.

## Scope

This contract applies to screens as they are touched. Retrofitting the whole
frontend at once is not the intent — the rule is that a screen you are editing
comes up to it.
