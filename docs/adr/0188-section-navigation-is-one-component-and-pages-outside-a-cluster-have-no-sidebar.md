# ADR-0188: Section navigation is one component, and pages outside a cluster have no sidebar

- **Status**: accepted
- **Date**: 2026-10-09
- **Deciders**: Mahdi Amirabdollahi
- **Amends**: [ADR-0034](0034-collapsible-sidebar.md),
  [ADR-0185](0185-settings-apply-as-one-change-set-and-settings-and-administration-are-grouped.md) (item 4)

## Context

Settings and Administration listed their sections with Mantine's vertical `Tabs`. Mantine centres a tab's
label and sets its own padding, so labels were centred while group headings were left-aligned; our CSS fought
it at equal specificity. Switching a section reset the window's scroll (the router resets it on every
navigation unless told not to), focused the panel with a scroll, unmounted the old section and showed a
loading frame for the new one, so the page jumped and blinked. The sidebar was shown on pages outside any
cluster (Administration, the inbox, the account, approvals) where it held only a cluster picker, and the way
back to a cluster was to open that picker and choose again. Configuration put every section of a declaration
on one long tab.

## Decision

1. **One row, one group heading.** `NavItem` and `NavGroup` are the only navigation row and heading: a
   full-width block, left-aligned, 8px/16px padding and a 38px row, label and heading on the same start edge
   (the `--as-nav-*` tokens), whole row the link, hover, `:focus-visible`, the open row highlighted with the
   accent bar and `aria-current="page"`, sentence case, an ellipsis with a `title` only when truncated.
2. **Sections are links, not ARIA tabs.** `SectionNav` renders `?tab=<id>` links in a `nav`, the open
   section in a labelled group. Settings, Administration and Configuration use it. The list is sticky and
   scrolls itself, with sticky group headings, when taller than the window.
3. **A section change keeps the page still.** Links do not reset the scroll and push a history entry; the page
   scrolls only when the new section's top is out of sight; a keyboard user lands in the section without a
   scroll; the section's height is held; loading frames fade in after 150 ms; a section's code loads when the
   pointer reaches its link. The router never resets the scroll for a change of the page's own search.
4. **No sidebar outside a cluster.** The shell shows it only under `/clusters/$id`. Other pages get a line
   above them: "Back to `<cluster>` · `<view>`" (the exact view and filters, kept in `lastPlace`) and where they are.
   The header brand goes to the same place.
5. **Configuration is a section list:** Declaration (five sections, each with its count and, in words and a
   mark, how many differ on a node), Nodes, Changes (history, recommended); `?tab=` names the section and
   `?item=`/`?add=` its open editor.
6. **Pair rules report on the missing member and re-check on every edit,** so a message on one field of a
   pair clears when the other is filled.

## Consequences

- Plugin Administration tabs render as links; tests that found `role="tab"` find `link`.
- Pages outside a cluster gain the sidebar's width; the way back is one click.
- Deep links to Configuration's old `?tab=declared` and `?section=` open the first section.

## Alternatives considered

- **Keep Mantine `Tabs` and override the CSS harder.** Rejected: it is the wrong widget (these are
  navigation, not a tab widget) and the specificity fight returns with every Mantine update.
- **Move Administration's menu into the application sidebar.** Rejected: it needs a portal, and the icon rail
  has nowhere to put labelless rows.
- **Keep every panel mounted so nothing flashes.** Rejected: every section's queries would run at once.
