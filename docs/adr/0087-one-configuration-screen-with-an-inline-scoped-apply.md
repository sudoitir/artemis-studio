# ADR-0087: one configuration screen, with an inline apply that can be scoped to one item

- **Status**: accepted
- **Date**: 2026-09-20
- **Deciders**: Studio maintainers

## Context

ADR-0067 gave the declared configuration a plan → confirm → result flow (D3, D4,
D7). The screen that grew around it split the operator's one question — *what does
this cluster run, and how far is it from what I declared?* — across four places:

- a **Declared** tab with the items and an editor,
- a **Drift** tab with the same items again, grouped by node and by finding kind,
- a small header button to a **separate address**, `/configuration/apply`, holding
  a 453-line three-stage page,
- a **History** tab for what was applied.

Three things follow from that split, and all three were reported by an operator.

*Saving looks like applying.* An editor's save writes a revision and closes. The
screen it returns to says "Revision 4"; nothing on it says that no broker has
been written. The next sentence an operator needs — *and it is not on any node
yet* — is a tab away, in a different vocabulary ("drifted"), next to a node name
rather than next to the item they just edited.

*Drift and the declaration are the same list, twice.* An item's declared value is
on one tab and its live state on another, so comparing them is navigation. Neither
tab can act: the fix for a finding is to leave, find the apply page, and apply the
whole declaration.

*The unit of apply is the whole declaration.* An operator who changed one
address setting has to preview, read and confirm every pending step on the cluster
— including steps someone else left pending — or apply nothing. Rather than
confirm a plan larger than their intent, the safe habit is to not apply at all.

## Decision

**D1 — One screen.** The Configuration screen is one desired-vs-live view. Each
declared item is a row carrying its declared value, its live state per node *in
words* ("in sync on 2/2", "differs on broker-2", "missing on broker-1"), the
difference where there is one, and its actions. The Drift tab and its route-level
twin are removed; what has no declared row — an undeclared resource, an unreadable
node, a node not evaluated — keeps a panel of its own, because it is about nodes,
not about items.

A persistent status bar states the revision and how far it has got: *"Revision 4 —
applied to 0 of 2 live nodes"*. That sentence is what a save produces, so an
edit's consequence is stated by the screen the editor returns to, with no new
state to keep in sync.

**D2 — Apply is a drawer on that screen, and can be scoped to one item.** Plan,
hazards, confirmation and per-node result move into a drawer over the rows they
describe, so what is being confirmed is next to what it changes. `ApplyView.tsx`
and `/clusters/:id/configuration/apply` are deleted; there is no compatibility
redirect.

A row's **Apply this** opens the same drawer narrowed to that item. The narrowing
is a set of plan step identifiers (`SECTION:key:OP`) on `ApplyRequest`, and
`BrokerConfigPlanner.restrict` applies it to a planned `Plan`:

- steps whose identifier is not named are dropped, on every node;
- hazards belonging to a dropped item are dropped with it — a hazard is a
  consequence of a step, and asking for an acknowledgement of one that will not
  run teaches an operator to acknowledge without reading;
- findings, violations and the canary are unchanged: the node order is the same
  run, shortened;
- `planHash` and `stepCount` are recomputed over what is left, so ADR-0067 D12's
  "the plan you confirmed is the plan that ran" covers the scoped run exactly. A
  preview and its real run must name the same step identifiers or the hash
  refuses.

An identifier that matches nothing is ignored rather than refused: the client
names the three ops an item could take without knowing which the plan chose, and
a scope that ends up empty is an apply with nothing to do, which the plan already
says in words.

**D3 — Everything else about an apply is unchanged.** Canary first, read back and
verify, halt on the first failure, no rollback (ADR-0067 D3/D4). High hazards are
acknowledged one by one; the confirmation is still the cluster's name, typed, in
`ui/ConfirmByTyping.tsx`. A `CONFIG_MANAGED` cluster still disables the control
with its reason and offers the broker.xml fragment (D2 of ADR-0067,
non-negotiable #5). A scoped apply is audited like any other, and its audit
parameters record the step identifiers it was given.

## Consequences

- The screen answers the operator's question without navigation, and a save's
  consequence is on the screen the save returns to.
- A narrow change can be applied narrowly, which is the difference between an
  apply that gets confirmed and one that gets deferred.
- The whole-cluster apply is still the default and still one click: scoping is a
  row's action, never the primary one.
- `planHash` now depends on the scope, so a client that previews unscoped and
  runs scoped is refused. That is the intended behaviour and the reason the hash
  covers the narrowed plan rather than the whole one.
- Two removals are permanent: the apply route and the drift tab. A bookmark to
  `/configuration/apply` 404s. Per the project's no-compatibility rule, no
  redirect is added.
- The open editor stays navigable state: `?section=&item=` names the drawer a row
  opens, so a half-made edit can be linked and restored. The `tab=drift` value is
  the only search state the redesign removes.
- The row's state-in-words is computed in the browser from the stored per-node
  findings, as the Drift tab's was. It is only as fresh as the last evaluation,
  so the status bar keeps the evaluation's age and the "Evaluate now" action.

## Alternatives considered

**Keep the apply page and link to it from the rows.** Cheapest, and it leaves the
central problem — confirming a plan somewhere other than where its consequences
are listed — exactly where it was.

**Scope by re-planning from a filtered document.** The client would send a subset
of the declaration and the server would plan against that. It changes what
"revision N" means mid-apply, and a removal step depends on items the subset drops.
Narrowing the computed plan keeps one planner and one meaning of a revision.

**Scope by `(section, key)` pairs rather than step identifiers.** Fewer strings on
the wire, but it makes the server responsible for matching a client's idea of an
item against the plan's. Step identifiers are already stable, already on the wire
in the preview, and already what the hash is built from.
