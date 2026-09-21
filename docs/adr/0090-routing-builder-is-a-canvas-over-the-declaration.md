# ADR-0090: The routing builder is a canvas over the declaration, not over the broker

- **Status**: accepted; D3's host screen (a tab of the Configuration screen) superseded by [ADR-0094](0094-routing-builder-is-hosted-by-the-routing-screen.md)
- **Date**: 2026-09-20
- **Deciders**: Artemis Studio maintainers

## Context

Roadmap item *B · Routing builder* asks for a visual builder for diverts, bridges and
transformers. Most of what that needs is already here, and the pieces disagree about who may
write to a broker.

`feature/routing` creates and deletes diverts live, through `BrokerCommands`, with preflight,
per-node outcomes and a generated `broker.xml` fragment. `feature/brokerconfig` holds a
declaration — what a cluster is *configured to be* — and applies it canary-first, with a plan,
named hazards, an acknowledgement gate and a per-node drift report. ADR-0087 collapsed that
into one screen with an inline apply that can be narrowed to a single item. ADR-0071 says
every cluster-wide broker write goes through one audited executor, and `AuditCoverageTest`
enforces it.

A canvas is a tempting place to put a third write path. Dragging a line between two addresses
looks like an action, and the obvious implementation is to make it one.

There is also a contract in the way. `operator-ui` requires that a previewed mutation is
exactly what is submitted, that a preview's inputs are frozen while it is shown, and that
changing a value forces a new preview. A canvas whose every drag is a mutation preview fights
that on every interaction.

## Decision

We will build the routing builder as **an editor of the declaration**, and as **a mode of the
one configuration screen**.

**D1 — The canvas writes a document, not a broker.** Composing a route changes
`BrokerConfigDocument` in the browser. The only commit is saving a revision, against the
revision the document was read from, so two operators editing one cluster collide loudly. No
broker is contacted until the operator applies, and applying is the existing plan → hazards →
canary → verify → drift path. There is no second write path and no per-element "apply now" on
the canvas; ADR-0087's scoped apply already narrows an apply to one item, from the plan, where
the hazards are.

**D2 — Authoring is not previewing.** This is what reconciles a direct-manipulation canvas
with `operator-ui`. The canvas authors; it claims nothing about what a broker will do. The
preview remains the apply drawer, computed from a saved revision, with frozen inputs and a
plan hash recomputed on the way into the confirmation so a cluster that moved is reported as a
new plan to review rather than as a refusal after the operator has typed the cluster's name.
A future contributor's instinct will be to make the canvas act on the broker; this is the
sentence that says why not.

**D3 — It lives in `feature/brokerconfig`, as a tab on that screen.** The configuration
screen's view modes are Mantine `Tabs` in `ConfigurationView.tsx`; `ModeControl.tsx` is the
*apply*-mode popover and is not where a view goes. The builder is a tab beside Declared and
History, with its anchor and selection in `ConfigurationSearch`. The canvas edits the declaration, so it belongs
in the module that owns it. A canvas in `feature/routing` would need a new frontend edge and a
new backend dependency to reach the document, and would put an editor of the declaration
outside its owner. A new feature module would re-declare brokerconfig's entire dependency set
to edit brokerconfig's document. Neither buys isolation. The builder inherits the
`brokerconfig` toggle, which is the correct blast radius.

**D4 — The graph is never the only way.** Every element the canvas shows and every edit it
offers stays reachable from the Declared tab, which is a complete equivalent rather than a
degraded mode. The canvas is keyboard-operable — focusable nodes with accessible names naming
what they are and what they connect, arrow-key traversal, Enter to open, Escape to leave — and
it is bounded, stating what it has left out rather than silently truncating.

**D5 — Nothing on the canvas animates.** Unlike the Flow screen next door (ADR-0080), this
graph has no motion, so `operator-ui`'s pause, reduced-motion and off-screen requirements are
satisfied by construction. Recorded because the two screens will be compared.

**D6 — Element state is declared-versus-observed, never origin.** The canvas states one of
three facts in words: declared and observed, declared and not yet applied, observed and not
declared. None is a claim about where anything came from. ADR-0065 D2 withdrew that vocabulary
because it is not derivable, and "observed and not declared" is a statement about Studio's
declaration, not about the broker's history.

## Consequences

Good: one write path, one audit story, one apply. The builder gets hazards, the canary, the
bulk discipline, the drift report and `broker.xml` export for free, and cannot drift from the
table view because both open the same editors.

Good: no new module, no new frontend boundary edge, no new nav group, no new permission, no
new dependency — `@xyflow/react` and `elkjs` are already here, and the ELK worker moves to
`ui/graph/` exactly as ADR-0080 anticipated.

Bad: composing a change and having it take effect are two steps, and an operator who wants to
drag a line and see traffic move will find that slower. That is the intended trade: the
in-between step is where the blast radius is stated.

Bad: the declaration becomes the only route to a bridge, so a cluster whose configuration is
managed elsewhere gains a second place where routing is described. The drift report is what
keeps those two honest, and it is the same trade the declaration already makes for address
settings and diverts.

We are committed to keeping the Declared tab a complete equivalent. If a capability ever
appears only on the canvas, D4 has been broken and the graph has become load-bearing.

## Alternatives considered

- **A canvas that creates diverts live through the existing routing API.** The shortest path,
  and it already works for diverts. Rejected: it is a second write path for configuration that
  the declaration exists to own, it cannot express a bridge at all without a third, and it
  would produce exactly the drift the declaration was built to close.
- **A separate "Routing builder" screen with its own apply.** Rejected by ADR-0087 — a second
  apply surface means a second plan presentation, a second hazard acknowledgement and a second
  chance to diverge.
- **A free-form XML editor on a canvas.** Rejected for the reason ADR-0067 D1 already gives:
  the declaration is the model and XML is an interchange format; errors in pasted XML cannot be
  mapped to a field, and XML can express what the management API cannot apply.
- **Making the canvas a live view with edit-in-place on the broker.** Rejected: it is D2
  inverted, and it would make every drag a mutation whose preview contract cannot be met.
