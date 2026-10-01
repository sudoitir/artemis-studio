# ADR-0165: The UI is verified in a real browser

- **Status**: accepted
- **Date**: 2026-10-01
- **Deciders**: Mahdi Amirabdollahi

## Context

ADR-0024 chose Vitest with jsdom, Testing Library and MSW, and it serves behaviour well. jsdom has no
layout: every width is 0, every `scrollWidth` is whatever a test mocks, fonts never load, and CSS
modules are not applied. The column-fitting defect ADR-0161 fixes lived exactly there. The tests
mocked `scrollWidth`, passed, and the grid scrolled sideways in every real browser.

Layout, contrast, focus visibility, layout shift and the Content-Security-Policy can only be checked
where they happen. Screenshots taken by hand cover a few pages in one scheme, not every route in
every state.

## Decision

We will verify layout and accessibility in Chromium, in two places.

- **Vitest browser mode, in CI.**
  - `vitest.config.ts` has two projects. `unit` runs on jsdom, as ADR-0024 set, and excludes
    `*.browser.test.*`. `browser` runs those files in Chromium through `@vitest/browser-playwright`,
    with `css: true`, its own setup (Mantine styles, `theme.css`, the fonts, waiting for
    `document.fonts.ready`), no jsdom stubs and no network.
  - Coverage merges both projects into one report.
  - `src/app/tables.browser.test.tsx` renders every grid's real columns, from each view's `columns.ts`
    factory, at 960 and 1920 px with normal and long fixtures. It asserts the expected visible
    columns per width (so "no overflow" cannot pass by hiding everything), no inline overflow, header
    and cell alignment, sticky edges when forced to overflow, middle truncation keeping suffixes
    distinct, resize steps of exactly 16 px, and no axe violations in either scheme.
  - Each page part and the static table has one axe-and-layout browser test.
  - The CI frontend job installs Chromium and runs the browser project; its timeout rises from 20 to
    30 minutes.
- **A screenshot sweep against an isolated stack, before a UI change merges.**
  - A compose overlay runs Studio and its brokers under their own project name, on their own Studio
    port and random broker ports, so it never collides with another running stack. A seed script
    loads long and large content: long names, many queues, DLQ messages with long bodies, many
    clusters, a signed plugin.
  - `web/scripts/sweep.ts` (Playwright) captures every route at three widths in light, dark and
    system schemes, plus 1280 px at 200% zoom, in every state: loading, error, empty, filtered,
    long content, forbidden, unreachable, and mutation outcomes. It runs against the production build
    served by Studio, so the real CSP and production React are what is checked.
  - Every capture checks axe (WCAG 2.2 AA including target size), page and table overflow,
    cumulative layout shift from navigation start (budget 0.01), CSP violations, console errors and
    any request to another origin. A failed check fails the sweep.

This amends ADR-0024: jsdom stays the harness for behaviour, and layout and accessibility are tested
in a real browser.

## Consequences

- A layout defect fails a test in CI instead of reaching a release, and a column fix cannot pass on
  a mock.
- CI runs slower and downloads Chromium. One browser and one project keep that bounded.
- Browser tests are harder to debug than jsdom ones, and timing (font loading, observers) must be
  awaited explicitly.
- The sweep needs Docker and minutes per run, so it runs before a UI change merges rather than on
  every push. Its findings are fixed at every severity.
- Firefox and WebKit layout differences are not covered.

## Alternatives considered

- **More jsdom mocks.** They encode what the author believes the browser does, which is how the
  `scrollWidth` defect passed.
- **Playwright component testing.** A second runner and config beside Vitest for what Vitest's
  browser mode does in the same suite.
- **Visual diff snapshots in CI.** Every font or token change rewrites the baselines, and a diff says
  that pixels changed, not that a rule is broken. Explicit checks (overflow, axe, CLS) state what
  failed.
- **All three browser engines.** Triples CI time; Chromium is the engine the defects were found in.
