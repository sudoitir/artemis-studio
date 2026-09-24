# ADR-0069: Studio is a kernel + plugin modular monolith, composed at build time and verified by Spring Modulith

- **Status**: accepted; superseded in part by [ADR-0099](0099-runtime-plugins-are-child-contexts-installed-from-the-ui.md) (runtime plugins; built-ins stay composed at build time)
- **Date**: 2026-09-13
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/kernel-plugin-architecture`

## Context

The backend is organised by layer (`web/ service/ persist/ broker/ domain/ scheduler/ sse/ mcp/`) and the frontend by screen, with one hand-written API client for every feature. Thirty-two capabilities in, adding one means editing central registries: `SettingsService.REGISTRY`, `DynamicSchedules`, `StreamController.KNOWN_TOPICS`, `McpDiagnosticTools`, `NAV_ITEMS`, the topic chain in `stream.ts`, and `router.tsx`. Features reach into each other's tables and services. `ClusterService` deletes alert rules, and the scraper calls the alert evaluator. Nothing enforces a boundary, so every new feature makes the next one more expensive.

Studio is one self-hosted image talking to brokers that must never be overloaded. Operationally it must stay one process with one database. The problem is internal structure, not deployment.

## Decision

We will organise Studio as a **modular monolith** in three layers, feature-first on both backend and frontend:

- **Kernel** (`kernel/*`): contracts plus the cross-cutting enforcement every module relies on (core, plugin, security, audit, settings, jobs, stream). A kernel module never references a platform or feature type.
- **Platform** (`platform/*`): shared broker-facing infrastructure (broker transport, cluster registration, scraping, the MCP server). Only MCP may be disabled.
- **Feature plugins** (`feature/*`): everything else. Each is one backend module and one `web/src/features/<id>` folder with the same id.

Boundaries are enforced in `./mvnw verify` by **Spring Modulith** (`ApplicationModules.verify()`, with `spring.modulith.detection-strategy=explicitly-annotated` and `@ApplicationModule(allowedDependencies = …)` in each `package-info.java`) and by **ArchUnit** rules for what Modulith does not cover (framework, persistence and broker-client access). Modules expose only the named interfaces `api`, `spi` and `events`.

Composition is **at build time**:
- `app/StudioFeatures` `@Import`s every feature configuration, and `web/src/app/features.ts` imports every frontend feature. These are the only two lists.
- Each non-required feature is gated by the startup property `artemis-studio.features.<id>.enabled` (default `true`), read by a `@ConditionalOnBooleanProperty`-based `@ConditionalOnFeature`.

The project stays one Maven module.

## Consequences

- Adding a feature means adding a module and one line in each composition root. No central registry is edited.
- Boundary violations fail the build instead of accumulating. The first moves must untangle the existing upward calls, replacing them with events and SPIs.
- A disabled feature is structurally absent (no beans, endpoints, jobs, tools or topics). Its tables still migrate, so re-enabling needs no migration.
- There is no runtime plugin loading: a new feature needs a new build. We accept that; Studio ships as one image.
- Modulith's module model and generated canvases become part of the documentation.
- One Maven module means the compiler does not stop a forbidden import; the verification test does. A forbidden import is caught at `verify`, not at compile time.

## Alternatives considered

- **Maven multi-module (kernel-api, kernel, one module per feature, app).** Compiler-enforced boundaries, but about 25 poms, slower builds and duplicated Lombok/MapStruct/Spotless setup for a single deployable. Rejected.
- **ArchUnit only.** Would re-implement cycle detection and internal-package rules that Modulith provides.
- **Runtime plugins (PF4J, OSGi, separate jars) or micro-frontends.** Classloader and versioning complexity with no operational benefit for a single self-hosted image.
- **Keep the layered layout and add conventions.** Conventions are what has already eroded.
