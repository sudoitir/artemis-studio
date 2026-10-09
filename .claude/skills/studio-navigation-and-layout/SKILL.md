---
name: studio-navigation-and-layout
description: >
  Use when adding or changing navigation in Artemis Studio's web UI: a sidebar row, a group heading,
  a list of a page's sections (Settings, Administration, Configuration), the application shell, the
  way back from a page outside a cluster, or when a section change makes the page jump, scroll to
  the top, blink or show a spinner. Gives the one component to use, the geometry tokens, the scroll
  and focus rules, and the grep traps that found each bug before.
---

# Studio navigation and layout

Studio's navigation had these defects once: labels centred while group headings were left-aligned; the
page jumping to the top and blinking when a section was chosen; a sidebar that held only a cluster picker
on pages outside a cluster; Configuration with every section on one long tab. Each came from a specific
mistake below. Check your change against them.

## The parts (use these, add no CSS of your own)

| Need | Use | Where |
| --- | --- | --- |
| A row in the sidebar or a section list | `NavItem` | `web/src/kernel/shell/NavItem.tsx` |
| A heading over rows | `NavGroup` | `NavGroup.tsx` |
| A page's sections, one open at a time | `SectionNav` (groups of `{id, title, panel, aside?, preload?}`) | `SectionNav.tsx` |
| The way back on a page outside a cluster | `GlobalContext`, `lastPlace` | `GlobalContext.tsx`, `lastPlace.ts` |

- **Section list or tabs?** Sections of a page that have their own content, a count or a state, more than
  about four, or that need group headings: `SectionNav`. Two or three views of one thing (Waiting/Mine):
  horizontal Mantine `Tabs`. Never `Tabs orientation="vertical"`: Mantine centres the label and sets its
  own padding, and our CSS then fights it at equal specificity.
- **Sections are links.** `?tab=<id>`, the open one `aria-current="page"`, the first when the address names
  none. Tests find `link`, `navigation` and the section's `group`, not `tab`/`tablist`/`tabpanel`.
- **`keep=[...]`** on `SectionNav` lists the search values that survive a change of section (a page filter);
  anything else belongs to the section that was open and is dropped.

## Geometry (tokens in `theme.css`, never px in a component)

`--as-nav-pad-inline` 1rem, `--as-nav-pad-block` 0.5rem, `--as-nav-row-h` 2.375rem, `--as-nav-group-gap` 1rem.
A row is `display:flex; inline-size:100%; justify-content:flex-start; text-align:start`; the whole row is the
link. A group heading uses the same inline padding, so heading and label share one start edge. The open row
has a full-row background and the accent bar as a `::before`, so the bar never shifts the label. Labels are
sentence case and truncate with an ellipsis; a `title` only when it is actually truncated (`useTruncated`).
The list is `overflow-y:auto` and sticky with sticky group headings. Logical properties only.

## Scroll, focus and flicker

- **The router resets the scroll on every navigation** unless told not to. `app/router.ts` wraps `navigate`
  so `to: '.'` keeps it; a link to a section sets `resetScroll={false}` (`NavItem` does when `current` is given).
  Grep traps: `navigate({ to: '.'` with `resetScroll: true`; `scrollTo(0`; `.focus()` without
  `{ preventScroll: true }` after a navigation; `scrollIntoView` without a reason.
- **Focus.** A keyboard user who activates a section link lands in the section (`preventScroll`); a pointer
  user's focus stays on the link.
- **No collapse, no flash.** The open section holds a `min-block-size`; `LoadingState` is in the DOM at once
  but fades in after 150 ms (CSS `animation-delay`, not a timer); a lazy section exposes `.preload`, which the
  list calls on pointer-enter or focus. Do not set `keepMounted` to hide a flash: every section's queries
  would run at once.
- **A section's panel must not size the page by what is loaded.** Reserve the height of what will arrive.

## The shell

The sidebar exists only under `/clusters/$clusterId`. Every other page (Administration, inbox, account,
approvals, plugin root pages) has none and shows `GlobalContext`: "Back to <cluster> · <view>" to the exact
view and filters the operator left, and where they are. The header brand goes to the same place. Do not put
a section in the sidebar that is not about the open cluster, and do not add a second way to pick a cluster.

## Checklist before merging

1. No new navigation CSS; rows and headings are `NavItem`/`NavGroup`.
2. Labels and headings share a start edge at 100% and 200% zoom, both schemes (look at it; do not infer).
3. `aria-current` is on exactly one link; focus ring is visible; hover is visible.
4. Ten section switches with the page scrolled halfway: `scrollY` unchanged, no layout shift, no spinner flash.
5. A long list (many plugin tabs) scrolls inside the nav with the heading sticky.
6. A page outside a cluster has the back line and no sidebar; a cluster page has the sidebar and no back line.
7. Tests assert roles, names and `aria-current`, and that `window.scrollTo` was not called.
