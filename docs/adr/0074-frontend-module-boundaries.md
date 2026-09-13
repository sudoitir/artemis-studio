# ADR-0074: Frontend module boundaries are enforced with eslint-plugin-boundaries

- **Status**: accepted
- **Date**: 2026-09-13
- **Deciders**: Mahdi Amirabdollahi
- **Depends on**: [ADR-0069](0069-kernel-plugin-modular-monolith.md), [ADR-0070](0070-extension-contract-and-feature-manifest.md)

## Context

The frontend has no import restrictions. There are eight feature-to-feature imports today; `clusters/` and `topology/` import each other. The shell imports feature internals, and the API and stream modules import app-level helpers. Feature-first folders (ADR-0069) and slots (ADR-0070) only stay meaningful if something fails when a feature reaches into another feature or the kernel reaches into a feature.

## Decision

`web/eslint.config.js` uses **eslint-plugin-boundaries** (7.2.0, `boundaries/dependencies`, `default: "disallow"`) over these elements:
- `kernel` = `src/kernel/**`
- `ui` = `src/ui/**`
- `feature` = `src/features/*`, capturing the id
- `app` = `src/app/**`, the composition root
- `test` = `src/test/**`

Policies:
- `ui` imports nothing app-specific except the generated DTO types in `kernel/api/schema.d.ts`.
- `kernel` imports `ui`.
- A `feature` imports `kernel`, `ui` and its own files.
- A `feature` may import another feature only through that feature's `index.ts`, and only for the edges listed in the change design:
  - `rr → queues`
  - `sql → messages, queues`
  - `brokerconfig → messages`
  - `audit → security`, `apitokens → security`
  - any feature → `clusters`
- `app` imports everything.
- Tests follow the rules of the element they test, and may also use the shared harness in `test/`.

The rule starts at `warn` while the tree is being moved and becomes `error` once every folder lives in its element. `npm run lint` in CI then fails on a violation.

## Consequences

- Cross-feature needs become explicit: either a slot (ADR-0070) or a named public export on an allowed edge.
- The allowed feature edges are listed twice: in `package-info.java` (backend) and in the eslint policy (frontend). A new edge is added to both deliberately.
- Lint gets somewhat slower, since the plugin resolves imports.

## Alternatives considered

- **`no-restricted-imports` patterns.** Cannot express "a feature may import itself but not its siblings" without one pattern per feature.
- **`eslint-plugin-import` `no-restricted-paths`.** Workable, but zones must be listed pairwise and there are no captured element ids.
- **TypeScript project references per feature.** Compiler-enforced, but multiplies `tsconfig` files and slows builds for a single SPA.
- **Nx or another monorepo tool.** A build system adopted for one lint rule.
