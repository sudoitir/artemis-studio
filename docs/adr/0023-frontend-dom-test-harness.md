# ADR-0023: Frontend DOM test harness is Vitest + Testing Library + MSW

- **Status**: accepted
- **Date**: 2026-09-04
- **Deciders**: Mahdi Amirabdollahi

## Context

`CONTRIBUTING.md` already states the intended frontend testing altitude:
"Testing Library by role, MSW for the network". The repo does not have it. The
only frontend test is `web/src/topology/layout.test.ts`, a pure-logic file run
through Node's built-in `node:test` with `--experimental-strip-types`. There is
no DOM environment, no component rendering, no network mocking.

Phase 2 deferred two component tests explicitly for this reason — `QueueGrid`
(task 7.5) and `CommandPalette` (task 10.3) — with the note that a DOM harness
"needs vitest; its esbuild postinstall is blocked by this env's allow-scripts
policy".

Phase 3 ships the highest-stakes UI in the product so far: a typed-confirmation
purge, a bulk-action preview gated on a cap, a truncation banner that must appear
exactly when a body is clipped. These are precisely the components where a
behavioural test earns its keep.

Adding a test runner is a new dependency, which needs an ADR.

## Decision

We will add a Vitest-based DOM harness.

- **Runner: Vitest.** It reuses the Vite pipeline the app already builds with
  (`vite@8`), so its transform/resolve behaviour matches production and there is
  no second bundler config to keep in sync. esbuild is already installed and
  working as a Vite dependency, so the Phase 2 postinstall concern does not apply
  to adding Vitest on top of it.
- **DOM: `jsdom`**, via `environment: 'jsdom'` in `vitest.config.ts`.
- **Assertions/interaction: `@testing-library/react`,
  `@testing-library/user-event`, `@testing-library/jest-dom`.** Queries by role
  and accessible name, per CONTRIBUTING and the frontend guide.
- **Network: `msw`.** A shared `server` in `web/src/test/setup.ts` with
  `beforeAll` / `afterEach` / `afterAll`. Components are tested against realistic
  API responses shaped from the generated schema, not hand-rolled `fetch` stubs.
- **Wiring.** `"test": "vitest run"` in `package.json`; `verify-web` becomes
  `build` + `lint` + `test`; the CI frontend job runs `npm test`. The one
  `node:test` file migrates to Vitest and the `--experimental-strip-types`
  script is dropped.
- **Fallback, recorded.** If `jsdom` cannot be installed under the environment's
  allow-scripts policy, use `happy-dom` instead; failing that, keep `node:test`
  with a hand-registered jsdom global for the component tests. Whichever is used
  is noted in this ADR's status when it happens. This is a contingency for the
  install environment, not a second supported harness.

## Status update (Phase 3 implementation)

Implemented as the primary path — no fallback needed.

- `jsdom@30` installed cleanly under the environment's allow-scripts policy;
  `happy-dom` / `node:test` contingencies were not used.
- Versions: `vitest@4.1.11`, `jsdom@30.0.1`, `@testing-library/react@16.3.3`,
  `@testing-library/user-event@14.6.7`, `@testing-library/jest-dom@6.9.1`,
  `msw@2.15.0`. Vitest 4 reuses the `@vitejs/plugin-react` transform via a
  standalone `web/vitest.config.ts` (the app `vite.config.ts` carries a
  dev-server proxy that has no place in the test run).
- `web/src/test/setup.ts` owns the MSW `server` (`onUnhandledRequest: 'error'`),
  `@testing-library/jest-dom` matchers, and three jsdom shims Mantine and
  `@tanstack/react-virtual` need: `matchMedia` (jsdom 30 ships one that throws),
  a `ResizeObserver` that fires once on `observe`, and non-zero
  `offsetWidth`/`offsetHeight` on `HTMLElement.prototype` — without the last one
  the virtualizer renders zero rows.
- `web/src/test/render.tsx` wraps a unit under `MantineProvider` +
  `QueryClientProvider` (retries off).
- Migrated `topology/layout.test.ts` off `node:test`; the
  `--experimental-strip-types` script is gone. New: `grid/VirtualTable.test.tsx`
  (rows, `aria-sort` cycle, empty label, row click) and
  `palette/CommandPalette.test.tsx` (opens on `mod+K`, filters, an invoked
  action calls `navigate` — `@tanstack/react-router` mocked, API via MSW).
- `verify-web` = `build` + `lint` + `test`; the CI frontend job runs `npm test`.
  10 tests, green.

## Consequences

- Component behaviour is testable: the purge confirmation arming only on an exact
  match, the bulk preview blocking over-cap until confirmed, the truncation
  banner's presence — all become assertions in CI rather than manual smoke.
- The two Phase 2 deferrals (`QueueGrid`, `CommandPalette`) can be written and
  are, as part of Phase 3.
- `verify-web` and CI get slower by the test run. Acceptable — it is unit/
  component scope, not a browser.
- Five new dev dependencies (`vitest`, `jsdom`, three `@testing-library/*`,
  `msw`). All dev-only; none ship in the bundle.
- Playwright / full E2E is still not in scope. The pyramid here is unit +
  component; journey coverage is a later decision.

## Alternatives considered

- **Jest.** Rejected — a second transform toolchain (babel/ts-jest or SWC config)
  parallel to Vite, for no gain over a runner that reuses the Vite config the app
  already has.
- **Keep `node:test` + a hand-rolled jsdom global.** Rejected as the primary
  choice — no `user-event`, no MSW integration, no `@testing-library/jest-dom`
  matchers; CONTRIBUTING already names Testing Library + MSW. Retained only as
  the last-resort fallback above.
- **`happy-dom` as the default DOM.** Faster than `jsdom` but with more
  compatibility gaps; kept as the first fallback if `jsdom` will not install, not
  the default.
- **No harness, manual smoke only.** Rejected — Phase 3's destructive UI is
  exactly what should not rely on a human remembering to click it.
