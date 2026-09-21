# ADR-0094: The routing builder is hosted by the Routing screen, and declares a queue inline

- **Status**: accepted; supersedes the host-screen clause of [ADR-0090](0090-routing-builder-is-a-canvas-over-the-declaration.md) D3
- **Date**: 2026-09-21
- **Deciders**: Artemis Studio maintainers

## Context

ADR-0090 D3 put the routing builder in `feature/brokerconfig` and made it a tab of the
Configuration screen, beside Declared and History. Two parts of that decision were argued for:
the builder edits the declaration, so its code belongs in the module that owns the declaration;
and a builder in `feature/routing` would need a new frontend edge and a new backend dependency to
reach the document. Both arguments are still right.

The third part — which *screen* shows it — was not argued for, and it is the part operators trip
over. An operator looking for "where do I build a divert" goes to Routing, which lists diverts
and bridges. The builder was reached from there only through a link to another screen's fourth
tab. Configuration is where an operator goes to apply what is declared, not to draw it.

The builder also had no way to create the queue a divert needs. A divert into an address with no
queue drops what it diverts, so composing a divert to a new address meant closing the editor,
switching to another screen, declaring the address and its queue, and coming back.

The kernel already has the mechanism for one feature to show a panel on another's screen without
importing it: a slot. `admin.tabs` is a tab-shaped one.

## Decision

**D1 — The builder is a tab of the Routing screen.** The kernel gains a `routing.tabs` slot
(`{ clusterId }`), modelled on `admin.tabs`: each contribution is a tab after Diverts and Bridges,
labelled with its title, whose id is the tab's `?tab=`. `feature/brokerconfig` contributes
`RoutingBuilderTab` with id `builder`, title "Builder". The path is Routing in the navigation,
then Builder: two clicks.

**D2 — Ownership does not move.** The builder's code, its editors and the declaration it edits
stay in `feature/brokerconfig`, so ADR-0090 D3's module argument holds unchanged and there is no
new feature edge. `RoutingBuilderTab` brings what the Configuration screen used to hand the
builder: the declaration query with its loading and error states, the write and apply gates, its
URL state, and the same `ReviewApplyDrawer` behind a "Review & apply" control in the builder's
toolbar. There is still one plan and one apply (ADR-0090 D1).

**D3 — Routing's URL carries a contributed tab's keys without knowing them.** The routing route
accepts any string `tab` and passes through `section`, `item`, `anchor` and `selected` as strings.
A contribution narrows them to what it accepts; an unknown `tab` falls back to Diverts.

**D4 — The Configuration screen's routing tab is removed, with no redirect.** Its tabs are
Declared & live, History and Recommended. `?tab=routing` on Configuration now opens Declared &
live. A disabled `brokerconfig` removes the Builder tab, as a disabled feature is absent from every
surface; the Bridges tab's empty state then does not point at it.

**D5 — A divert's target queue is declared inline.** The divert editor's "To address" is a
combobox of the declared addresses whose last option, when the typed address is not declared, is
`Create queue '<address>'…`. It opens a section inside the drawer — queue name, routing type,
durability — that saves the address and its queue to the declaration as their own revision
("Added queue X") and leaves the divert open against the new revision. The queue is *declared*,
not created on a broker: it shows on the canvas at once as declared and not yet applied, and it
reaches the brokers in the same apply as the divert, so the divert never goes live ahead of its
queue. This changes nothing in ADR-0090 D1 or D2. The builder's toolbar also has "Add queue",
which opens the address editor with a queue row to name.

## Consequences

Good: the builder is where an operator looks for it, and Routing becomes the one place for
routing — what runs (Diverts, Bridges) and what is declared (Builder).

Good: no new module, feature edge, dependency, permission or nav group; one kernel slot with one
consumer.

Good: composing a divert to a new address is one flow in one drawer, keyboard-complete.

Bad: the builder's URL keys now live on a route that does not own them, typed as plain strings
there and narrowed by the builder. A second contributor to `routing.tabs` that wants keys of its
own will have to add them to the routing route's pass-through list.

Bad: a saved link to `configuration?tab=routing` lands on Declared & live. There is no redirect,
by the project's rule against compatibility paths.

Bad: an inline queue is a revision of its own, so a divert composed with a new queue is two
revisions in the history rather than one.

## Alternatives considered

- **Keep the builder on Configuration and improve the link from Routing.** Rejected: it keeps the
  builder on the screen operators go to apply, not to author, and the link is still a jump to
  another screen's fourth tab.
- **Move the builder into `feature/routing`.** Rejected for the reasons ADR-0090 D3 gives: a new
  frontend edge and backend dependency to reach the document, and an editor of the declaration
  outside its owner.
- **Create the queue on the brokers at once, through the queues API.** Rejected: it is a second
  write path the builder does not have (ADR-0090 D1); the queue would show as observed and not
  declared, would appear only after a drift evaluation, and would go live ahead of the divert.
- **A nested modal for the new queue.** Rejected: a modal over a drawer splits focus and Escape
  between two layers; a section inside the drawer keeps the divert in view.
