# ADR-0100: Plugin UIs are Module Federation remotes sharing the host's React tree and SDK

- **Status**: accepted
- **Date**: 2026-09-22
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/plugins`
- **Builds on**: [ADR-0070](0070-extension-contract-and-feature-manifest.md), [ADR-0074](0074-frontend-module-boundaries.md)

## Context

A runtime plugin must be able to contribute routes, navigation, palette actions, slot panels and stream handlers inside the running console: the same theme, the same query client, the same router. The console is a Vite-built SPA whose router was created before any manifest fetch.

## Decision

- **The console is a Module Federation host** (`@module-federation/vite`, configured in `vite.config.ts` only).
- **Shared singletons are limited to the libraries that hold state or context:** `react`, `react-dom`, `@mantine/core`, `@mantine/hooks`, `@mantine/notifications`, `@tanstack/react-query`, `@tanstack/react-router`, and **`@artemis-studio/plugin-sdk`, which resolves to the host's own `web/src/sdk/`**. Versions are pinned exactly.
- **Plugins declare every shared dependency as `import:false`,** so the host always provides it: `clusterRoute` is the host's object.
- **Icons, charts and dates are bundled by the plugin**, because sharing them would defeat tree-shaking in the host.
- **Bootstrap is asynchronous.** Fetch the manifest, register and load remotes in parallel with a 10 s limit each, then create the router. A plugin that fails to load never blocks the console.
- **Plugin routes live under `/p/<id>/`,** with kernel catch-alls that explain a plugin's state.
- **Plugin slots, badges and palette sources render inside error boundaries.**

## Consequences

- Plugin screens look and behave like built-in ones.
- A major bump of a shared library requires a contract bump, and every plugin UI must be rebuilt.
- Modules that have already loaded cannot be unloaded, so the console offers a reload after plugins change.
- The host build changes for every screen, which is gated by a bundle-size and screenshot comparison.

## Alternatives considered

- **Iframes.** Isolated, but they lose the theme, the slots, the shared router and query cache, and keyboard flow across the frame.
- **Hand-written import maps or SystemJS.** They re-implement Module Federation's version negotiation.
- **Web components.** No shared React context, so Mantine and TanStack would run twice.
