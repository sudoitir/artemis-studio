# ADR-0034: Navigation is a two-section collapsible sidebar

- **Status**: accepted
- **Date**: 2026-09-05
- **Deciders**: Mahdi Amirabdollahi

## Context

`RootLayout`'s `AppShell` navbar has held only the cluster list since Phase 2,
carrying the docstring "desktop-first — no mobile breakpoint, no collapsing
navbar (steering decision, this session)". Per-cluster section navigation
(topology, queues, request-reply, …) has instead been a horizontal tab strip
inside `ClusterLayout`, which already overflows its container at 12 items —
`.viewStrip { overflow-x: auto }` was already compensating for a nav pattern
that had outgrown its shape. Phase 6 adds a 13th view (metrics), which was the
forcing function to fix this rather than add a 13th overflowing tab.

## Decision

**Move the per-cluster view nav into the sidebar, as a second section below the
cluster switcher, and make the sidebar collapsible to a 64px icon rail.** This
supersedes the "no collapsing navbar" steering note above — that note predates
the view nav needing a home, and a rail that stays present with icons (rather
than disappearing) is a different shape than the "collapsing navbar" the note
was declining.

The collapse is **not** `AppShell`'s `collapsed` prop, which removes the
navbar's width entirely and is meant for a navbar that should vanish below a
breakpoint. Instead `navbar.width` is animated between 264 and 64 under
`AppShell`'s own `transitionDuration`, which already transitions
`--app-shell-navbar-width` and the `Main` offset in lockstep — no hand-rolled
layout math.

Collapse state persists per browser via `@mantine/hooks`' `useLocalStorage`
(`as:nav:collapsed`), not a global store — one boolean local to `RootLayout`
doesn't need one (non-negotiable #9). Collapsed rows keep an accessible name
(`aria-label`) and a `Tooltip`, so nothing is lost for a keyboard or
screen-reader user; a cluster row also gets a two-letter monogram, since the
health mark alone doesn't say *which* cluster.

## Consequences

- The old view strip and its CSS are deleted outright — no dual navigation
  kept during a transition period, per the project's no-back-compat
  convention. Any deep link into a specific view route is unaffected (the
  routes themselves did not move); only the chrome around them did.
- The sidebar now needs the current `clusterId` (from the route) to decide
  whether to render the view-nav section at all — `RootLayout` reads it via
  `useParams({ strict: false })`, same pattern `ClusterLayout` already used.
- `⌘/Ctrl+B` is now a reserved shortcut alongside the existing `⌘K` (Spotlight)
  — both are announced in the header.
- A future mobile/responsive pass (still out of MVP scope) will need its own
  design; this ADR does not attempt one — `breakpoint: 0` stays.

## Alternatives considered

- **Keep the horizontal view strip, only add collapse to the cluster rail** —
  rejected: leaves the actual defect (12+ items in a strip with no room) in
  place; a 64px rail showing only cluster monograms with the view strip still
  overflowing above it fixes nothing the view strip already needed fixed.
- **A drawer/overlay for the view nav** — rejected: hides primary navigation
  behind an extra interaction for what is, on this desktop-only console, a
  permanently visible piece of chrome; Quick-reference navigation guidance is
  clear that primary nav should stay reachable, not tucked behind a toggle on
  every visit.
- **`AppShell`'s `collapsed` prop** — considered first, rejected once its
  actual behavior (zero width, no `Main` offset) was checked: it is the right
  tool for a navbar that disappears below a breakpoint, not for a rail that
  stays present at a smaller width.
