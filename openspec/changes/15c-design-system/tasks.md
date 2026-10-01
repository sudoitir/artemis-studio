## 1. Setup and decisions (PR A)

- [ ] 1.1 Fold the web minor and patch dependency updates into the branch and confirm build, lint and tests pass on them
- [ ] 1.2 Write ADRs: token scale; visual identity and bundled typefaces; colour scheme follows the system (amends 0005); one data table, two renderers (refines 0020, 0056); column solver over measured content (supersedes 0116); density as a viewer preference; shared page parts and contract version; desktop layout floor with zoom support; UI verified in a real browser (amends 0024)
- [ ] 1.3 Add "depends on 15c-design-system" to the 19 and 20 proposals

## 2. QA tooling (PR A)

- [ ] 2.1 `deploy/compose/compose.isolated.yaml`, `just qa-up` / `qa-down` (own project name and Studio port, random broker ports, long session idle timeout, signed-in storage state per seeded user)
- [ ] 2.2 `scripts/qa-seed.sh`: long and large content for every view, many clusters, a signed template plugin
- [ ] 2.3 `vite.config.ts` reads `STUDIO_API` for every proxy
- [ ] 2.4 `web/scripts/sweep.ts` + `sweep/routes.ts`: every route × 1920/1440/1280 × light/dark/system + 200% zoom × states; axe, overflow, CLS, CSP, console, foreign requests; replaces `ui-review.ts` and `verify-grid.ts`
- [ ] 2.5 Baseline sweep and a per-area QA log under `qa/`

## 3. Tokens, identity, scheme and density (PR A)

- [ ] 3.1 `theme.ts` primitives, the resolver passed to `MantineProvider`, `autoContrast`; `theme.css` semantic tokens, focus ring, motion, density, xyflow mapping, reduced motion
- [ ] 3.2 Bundle Atkinson Hyperlegible Next and Mono, preload them, add metric-matched fallbacks
- [ ] 3.3 `public/boot-prefs.js` (scheme + density before first paint) and a backend test that serves it as JavaScript
- [ ] 3.4 Three-state colour scheme control and palette action; density in the user menu and palette
- [ ] 3.5 A contrast test for every text and non-text token on every surface in both schemes
- [ ] 3.6 Replace every Mantine colour-name prop with semantic tokens

## 4. Page parts (PR A)

- [ ] 4.1 Page, PageHeader, Section, Toolbar with tests
- [ ] 4.2 EmptyState, ErrorState (one test per status mapping), LoadingState with tests
- [ ] 4.3 StatusBadge, Stat, DescriptionList with tests
- [ ] 4.4 ConfirmDialog (keyboard-only pass) and `notify` with tests

## 5. DataTable (PR A)

- [ ] 5.1 Column model and the pure solver with unit tests
- [ ] 5.2 Grid and static renderers, Measurer, column menu, states, sticky edges, sort announcement, middle truncation, table state; port the VirtualTable tests
- [ ] 5.3 Vitest browser project, browser setup, axe and layout tests for the table and every page part; CI frontend job and `just verify-web` run them
- [ ] 5.4 Move every grid call site to DataTable with a `columns.ts` factory; delete `VirtualTable`
- [ ] 5.5 `src/app/tables.browser.test.tsx` over every grid's real columns at 960 and 1920 px

## 6. Shell and kernel (PR A)

- [ ] 6.1 Root and cluster layouts, cluster context strip, breadcrumb, freshness, navigation (rem-based rail), command palette, shortcuts help
- [ ] 6.2 Restyle kernel and shared parts: charts, resource actions and links, time, plugin boot and boundary, step-up and second factor, route error, feature disabled, plugin unavailable, code editor, diagram, node outcome, pager, capability reason, redacted value, menus, links

## 7. SDK (PR A)

- [ ] 7.1 SDK exports, contract version bump, plugin template, plugins guide, architecture doc, frontend rule
- [ ] 7.2 Typecheck the plugin template against the packed SDK

## 8. Pages (PR B)

- [ ] 8.1 Queues, resources, routing
- [ ] 8.2 Messages, DLQ, events, audit
- [ ] 8.3 Broker configuration
- [ ] 8.4 Transfers, bulk, metrics, diagnostics, MCP
- [ ] 8.5 Flow, request-reply, consumer health, data lifecycle
- [ ] 8.6 Alerting, governance, setup review
- [ ] 8.7 Security, API tokens, settings, home, admin, account
- [ ] 8.8 Clusters, plugins, local identity, sign-in
- [ ] 8.9 Every finding in `qa/` fixed; sweep clean over all areas

## 9. Redesigns, cleanup, docs (PR C)

- [ ] 9.1 SQL console design appended to design.md, then implemented; keyboard and SQL guides updated
- [ ] 9.2 Topology design appended to design.md, then implemented
- [ ] 9.3 Delete unused parts, dead styles and responsive code; grep gates clean
- [ ] 9.4 Regenerate README and site screenshots
- [ ] 9.5 Final sweep, `just verify`, archive
