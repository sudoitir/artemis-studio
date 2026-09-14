# ADR-0070: A versioned extension contract, a server-published feature manifest, and a closed set of navigation groups

- **Status**: accepted
- **Date**: 2026-09-13
- **Deciders**: Mahdi Amirabdollahi
- **Amends**: [ADR-0034](0034-collapsible-sidebar.md) (navigation gains groups)
- **Depends on**: [ADR-0069](0069-kernel-plugin-modular-monolith.md), [ADR-0019](0019-openapi-generated-frontend-types.md)

## Context

With features as modules (ADR-0069), a feature needs a defined, small way to contribute what it contributes today. That includes:
- REST endpoints, permissions, runtime settings and scheduled jobs;
- SSE topics and MCP tools;
- routes, navigation entries, palette actions, SSE topic handlers, and sections inside shared screens (Settings, Admin, Account, the cluster header, the queue drawer, the metrics view).

Without a contract, each shared screen keeps importing features directly. Without a manifest, the UI cannot know what a given installation has enabled. Without grouping, navigation is one flat list that gets longer with every feature.

## Decision

**Contract version.** One integer (`1`), declared in `kernel.plugin` and in `web/src/kernel/feature.ts`. It is bumped only when a contribution type changes incompatibly. A mismatch fails the build (Java test and TypeScript literal type).

**Backend contribution.**
- **Static facts:** one `FeatureDescriptor` bean per module holds id, contract, title, kind, required, requires, permissions, settings, stream topics, MCP catalogue entries and API path prefixes.
- **Behaviour:** beans of kernel SPI types: `ScheduledJob`, `BrokerEventSink`, identity providers, health contributors.
- **Endpoints and MCP tools:** ordinary Spring annotations inside the module, loaded only when its configuration is active.
- **Assembly:** the kernel builds the permission catalogue, settings registry, topic registry and MCP tool catalogue from descriptors alone.

**Frontend contribution.**
- **Declaration:** `defineFeature({ contract: 1, id, routes, nav, palette, streamTopics, slots })` in the feature's `feature.ts`. Its `index.ts` holds only what other features may import.
- **Routes:** route objects under kernel roots (`rootRoute`, `clusterRoute`), composed by `app/router.ts`, which also declares TanStack Router's `Register`.
- **Slots:** typed and kernel-owned: `shell.header`, `shell.navbar`, `home.empty`, `cluster.header`, `cluster.registration.afterProbe`, `queue.detail.panels`, `metrics.panels`, `topology.node.marks`, `settings.sections`, `admin.tabs`, `account.sections`.

**Manifest.**
- `GET /api/v1/manifest` (authenticated) returns the contract version, every built-in feature with its enabled flag, the permission catalogue with owning feature, and the configured identity providers.
- The UI renders only enabled features. A deep link to a disabled feature explains itself and names the property.
- A disabled feature's API prefixes answer `404` with problem type `feature-disabled`.
- The manifest informs composition only. Authorization stays on the server.

**Id parity.** A backend test writes `web/manifest.snapshot.json`, the same pattern as `openapi.json` (ADR-0019). A frontend test asserts the frontend feature ids equal the snapshot's.

**Navigation groups.**
- A closed, ordered, kernel-owned catalogue: `observe`, `messaging`, `resources`, `configuration`, `activity`.
- Every navigation contribution names one group and an order.
- Expanded: groups have headings. Collapsed: they are separated and keep accessible names. An empty group is not shown.
- Adding a group is a kernel change.

## Consequences

- Shared screens stop importing features. Settings, Admin and Account render slot contributions.
- `api/client.ts`, the `stream.ts` topic chain, `NAV_ITEMS`, the palette groups and `router.tsx` are replaced.
- A feature can be reasoned about from its descriptor and its `feature.ts`.
- The contract is deliberately narrow. A need it does not cover is a contract change (a new contribution type or slot), recorded here or in a superseding ADR, not an ad-hoc import.
- The closed group catalogue trades flexibility for a coherent rail. A feature cannot invent a group.

## Alternatives considered

- **UI layout (nav, groups) carried in the backend descriptor.** The server would describe icons and screens it cannot render. The server owns what is enabled and permitted; the frontend owns layout.
- **Features define their own groups.** Groups would proliferate and ordering would be contested between features.
- **No manifest; the frontend infers enablement from `404`s.** Explanations would arrive late and inconsistently, and a missing feature would look like a broken one.
- **Semantic versioning per feature.** Everything ships in one build, so a single contract integer is enough.
