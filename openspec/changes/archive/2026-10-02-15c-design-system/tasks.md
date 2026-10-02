## 1. Setup and decisions (PR A)

- [x] 1.1 Fold the web minor and patch dependency updates into the branch and confirm build, lint and tests pass on them
- [x] 1.2 Write ADRs: token scale; visual identity and bundled typefaces; colour scheme follows the system (amends 0005); one data table, two renderers (refines 0020, 0056); column solver over measured content (supersedes 0116); density as a viewer preference; shared page parts and contract version; desktop layout floor with zoom support; UI verified in a real browser (amends 0024)
- [x] 1.3 Add "depends on 15c-design-system" to the 19 and 20 proposals

## 2. QA tooling (PR A)

- [x] 2.1 `deploy/compose/compose.isolated.yaml`, `just qa-up` / `qa-down` (own project name and Studio port, random broker ports, long session idle timeout, signed-in storage state per seeded user)
- [x] 2.2 `scripts/qa-seed.sh`: long and large content for every view, many clusters, a signed template plugin
- [x] 2.3 `vite.config.ts` reads `STUDIO_API` for every proxy
- [x] 2.4 `web/scripts/sweep.ts` + `sweep/routes.ts`: every route × 1920/1440/1280 × light/dark/system + 200% zoom × states; axe, overflow, CLS, CSP, console, foreign requests; replaces `ui-review.ts` and `verify-grid.ts`
- [x] 2.5 Baseline sweep and a per-area QA log under `qa/`

## 3. Tokens, identity, scheme and density (PR A)

- [x] 3.1 `theme.ts` primitives, the resolver passed to `MantineProvider`, `autoContrast`; `theme.css` semantic tokens, focus ring, motion, density, xyflow mapping, reduced motion
- [x] 3.2 Bundle Atkinson Hyperlegible Next and Mono, preload them, add metric-matched fallbacks
- [x] 3.3 `public/boot-prefs.js` (scheme + density before first paint) and a backend test that serves it as JavaScript
- [x] 3.4 Three-state colour scheme control and palette action; density in the user menu and palette
- [x] 3.5 A contrast test for every text and non-text token on every surface in both schemes
- [x] 3.6 Replace every Mantine colour-name prop with semantic tokens

## 4. Page parts (PR A)

- [x] 4.1 Page, PageHeader, Section, Toolbar with tests
- [x] 4.2 EmptyState, ErrorState (one test per status mapping), LoadingState with tests
- [x] 4.3 StatusBadge, Stat, DescriptionList with tests
- [x] 4.4 ConfirmDialog (keyboard-only pass) and `notify` with tests

## 5. DataTable (PR A)

- [x] 5.1 Column model and the pure solver with unit tests
- [x] 5.2 Grid and static renderers, Measurer, column menu, states, sticky edges, sort announcement, middle truncation, table state; port the VirtualTable tests
- [x] 5.3 Vitest browser project, browser setup, axe and layout tests for the table and every page part; CI frontend job and `just verify-web` run them
- [x] 5.4 Move every grid call site to DataTable with a `columns.ts` factory; delete `VirtualTable`
- [x] 5.5 `src/app/tables.browser.test.tsx` over every grid's real columns at 960 and 1920 px

## 6. Shell and kernel (PR A)

- [x] 6.1 Root and cluster layouts, cluster context strip, breadcrumb, freshness, navigation (rem-based rail), command palette, shortcuts help
- [x] 6.2 Restyle kernel and shared parts: charts, resource actions and links, time, plugin boot and boundary, step-up and second factor, route error, feature disabled, plugin unavailable, code editor, diagram, node outcome, pager, capability reason, redacted value, menus, links

## 7. SDK (PR A)

- [x] 7.1 SDK exports, contract version bump, plugin template, plugins guide, architecture doc, frontend rule
- [x] 7.2 Typecheck the plugin template against the packed SDK

## 8. Pages (PR B)

- [x] 8.1 Queues, resources, routing
- [x] 8.2 Messages, DLQ, events, audit
- [x] 8.3 Broker configuration
- [x] 8.4 Transfers, bulk, metrics, diagnostics, MCP
- [x] 8.5 Flow, request-reply, consumer health, data lifecycle
- [x] 8.6 Alerting, governance, setup review
- [x] 8.7 Security, API tokens, settings, home, admin, account
- [x] 8.8 Clusters, plugins, local identity, sign-in
- [x] 8.9 Every finding in `qa/` fixed; sweep clean over all areas

## 9. Redesigns, cleanup, docs (PR C)

- [x] 9.1 SQL console design appended to design.md, then implemented; keyboard and SQL guides updated
- [x] 9.2 Topology design appended to design.md, then implemented
- [x] 9.3 Delete unused parts, dead styles and responsive code; grep gates clean
- [x] 9.4 Regenerate README and site screenshots
- [x] 9.5 Final sweep, `just verify`, archive
