# ADR-0109: Navigation aids and single-key shortcuts

- **Status**: accepted
- **Date**: 2026-09-24
- **Deciders**: Artemis Studio maintainers

## Context

Navigation is the sidebar (ADR-0034) and a ⌘K palette (ADR-0005). An on-call operator
comparing clusters, or following a problem from a consumer to its connection, meets these
gaps:

- **Cluster switching:** switching cluster drops the view they were on.
- **The palette:**
  - it never sees the typed query;
  - it lists views without checking permission;
  - it opens queues as a filter rather than the queue;
  - its sources poll even while it is closed.
- **Orientation:** there is no `document.title` per view, no breadcrumb and no recents.
- **Links between resources:** resources do not link to each other.
- **Shortcuts:** there is no discoverable shortcut other than ⌘K and ⌘B.

ADR-0052 keeps refresh and pause off the keyboard because the browser owns ⌘R and ⇧⌘R.

## Decision

1. **Switching cluster keeps the view.** The rail and the palette link to the same view
   path in the other cluster, without its search. The search names resources that may not
   exist there.
2. **The console states where the operator is.**
   - A small kernel store holds the cluster, the view (matched from the navigation
     contributions) and the open resource.
   - The shell renders them as a breadcrumb and as the document title:
     `resource · view · cluster · product`. The product name comes from `branding.ts`.
3. **The palette searches without loading brokers.**
   - Sources receive the debounced query, and whether the palette is open.
   - A source fetches only while the palette is open and the query has two characters, and
     it never polls.
   - Live search covers what Studio already holds (the queue snapshot).
   - Live-read resources (connections, consumers and the rest) are offered as "Search
     connections for …" actions. These cost nothing until the view opens.
   - View entries the operator lacks permission for are listed disabled, with the reason.
   - A Recent group (browser-local, eight per cluster) and a visible Search button complete
     it.
4. **Single-key shortcuts are declared, listed and switchable.**
   - `g` followed by a letter goes to a view. The letter is declared by the navigation
     contribution and unique among built-ins; plugin hotkeys are ignored.
   - `?` lists every shortcut.
   - `/` focuses the view's filter. It is taken from the browser only when the view has
     registered one, so Firefox's quick find is kept elsewhere.
   - The engine ignores keys:
     - typed into editable elements (including `contenteditable`);
     - pressed with a modifier;
     - during IME composition;
     - already handled;
     - inside a dialog or a menu, so a confirmation can never be navigated away from.
   - A key from a non-Latin layout falls back to its physical key.
   - A personal setting turns single-key shortcuts off (WCAG 2.1.4). With it off, help is
     still reachable from the header's keyboard button.
5. **Browser-reserved shortcuts stay unbound** (ADR-0052 stands).

## Consequences

- An operator can move between clusters, views and resources without the mouse, and
  every tab says what it holds.
- `PaletteSource` and `NavContribution` gain optional fields, and the contract stays at
  version 1.
- A new single-key shortcut must be declared, and must be listed in help by construction.
- Recents live in `localStorage`: they are per browser and are lost with site data, which
  is acceptable for a convenience.

## Alternatives considered

- **Palette live search over every resource kind.** Each keystroke would be a Jolokia read
  per node (non-negotiable #1).
- **Letters derived automatically from view labels.** They would change as features are
  enabled or disabled, and muscle memory would break.
- **Shortcuts with no off switch.** That fails WCAG 2.1.4 for speech-input and single-switch
  users.
