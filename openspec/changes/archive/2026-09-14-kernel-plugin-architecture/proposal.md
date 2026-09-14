## Why

Studio's code is organised by layer (`web/ service/ persist/ broker/ scheduler/ sse/ mcp/`) and it has grown to 32 capabilities. Adding one now means editing a set of shared hub classes:

- the settings registry, the dynamic scheduler and the stream's topic list;
- the MCP diagnostic tool class and the frontend nav list;
- the frontend topic dispatcher and a 2,000-line API client.

Some features reach straight into another feature's tables: cluster removal deletes alert rules, and the scraper calls the alert evaluator. No test and no lint rule stops this. Studio is still pre-stable, which makes this the cheapest moment to reshape it into a durable platform. The target is a small kernel, shared platform modules, and feature plugins with enforced boundaries, and no change to what an operator can do.

## What Changes

- **Kernel + plugin modular monolith.** The code is reorganised feature-first on both backend and frontend. Each plugin is one backend module plus one frontend folder under the same id, and may contribute:
  - endpoints, permissions, settings and jobs;
  - stream topics and MCP tools;
  - health checks, nav items, routes and screen slots.

  It is still one jar, one image and one SPA, composed at build time.
- **Enforced boundaries.** The build fails when a module:
  - uses another module's internals, persistence, or the broker clients;
  - talks to the security framework outside the defined contracts;
  - forms a dependency cycle.

  The same rules apply on the backend and in the frontend lint.
- **Feature enablement.** Each non-required feature can be turned off at startup with `artemis-studio.features.<id>.enabled`. When a feature is off:
  - its endpoints, tools, topics, settings and screens are absent;
  - its deep links explain how to enable it.

  Required modules cannot be turned off.
- **Feature manifest.** A new authenticated read tells the UI which features are enabled, the permission catalogue they declare, and which identity providers are configured. The server stays the enforcement point.
- **Grouped navigation.** Each feature places its views in one of a fixed, ordered set of navigation groups. The cluster rail and the command palette show those groups.
- **Identity providers are pluggable.** Local password login, OIDC single sign-on and API tokens become three providers behind one contract:
  - the login screen is built from the configured providers;
  - group-to-role mapping is no longer OIDC-only but keyed by provider;
  - a new provider can be added as a module without touching the kernel or any feature.
- **BREAKING — group mapping API.** `/api/v1/oidc/mappings` is replaced by `/api/v1/identity/providers/{providerId}/group-mappings`.
- **One audited path for broker writes.** Every broker mutation runs through a single executor that owns the permission check, the audit row written before the broker call, dry run, the bulk cap and per-node outcomes. Behaviour does not change; the executor makes the existing guarantee structural.
- **Operational health.** Studio reports the health of its own background jobs, broker connections and notification subscriptions. This is kept out of liveness and readiness, so an unreachable broker never restarts Studio.
- **BREAKING — database re-baseline.** The schema history is replaced by one baseline per owning module. An existing database cannot be upgraded: operators must start from an empty database and re-register clusters, users, roles, tokens, channels and rules. Audit events no longer lose their cluster and node reference when a cluster is removed.
- **Parked:** in-flight change `03-message-replay-from-payload` is paused until this lands. Its tasks are then rewritten against the new messages module.

## Capabilities

### New Capabilities

- `feature-modules`: how features are composed into one installation. It covers what a feature declares, which modules are required, enabling and disabling at startup, the feature manifest, the contract version, and how a disabled feature behaves in the API, the MCP surface, the stream and the UI.
- `operational-health`: Studio's report on its own background jobs, broker connections and notification subscriptions, and its separation from liveness and readiness.

### Modified Capabilities

- `operator-ui`: grouped navigation; a deep link to a disabled feature explains itself.
- `identity-and-sessions`: login names a provider, and the login screen offers the installation's configured providers.
- `oidc-sso`: provisioning keyed by provider and subject; group mapping generalised to any provider, with a new endpoint.
- `authorization`: the permission catalogue is the union of what enabled features declare.
- `studio-settings`: a disabled feature's settings are neither listed nor writable.
- `mcp-server`: a disabled feature contributes no tools or catalogue entries, and the MCP surface itself can be disabled.
- `realtime-stream`: the recognised topic set comes from enabled features.
- `audit-log`: an audit event keeps its cluster and node reference after that cluster is removed.

## Impact

- **Backend:**
  - Every class under `src/main/java/io/github/sudoitir/artemisstudio/` moves into `kernel/`, `platform/` or `feature/<id>/`.
  - `ArtemisStudioProperties` is split per module.
  - The central registries (`SettingsService.REGISTRY`, `DynamicSchedules`, `StreamController.KNOWN_TOPICS`, `McpToolCatalog`, `Permissions`) are assembled from module contributions instead.
  - `ClusterService`, `ScrapeScheduler`, `McpDiagnosticTools` and `McpTuningTools` are split.
- **Frontend:**
  - `web/src/` is reorganised into `kernel/`, `ui/`, `features/<id>/` and `app/`.
  - `api/client.ts` is split per feature.
  - `router.tsx`, `stream.ts`, `navItems.ts`, and the Settings, Admin and Account views are replaced by contribution-driven equivalents.
- **Database:** `db/changelog/changes/001–025` is replaced by per-module baselines. `oidc_role_mapping` becomes `identity_group_mapping`; `app_user` gains provider identity columns; `audit_event` loses its foreign keys.
- **API:**
  - new `GET /api/v1/manifest`, `GET /api/v1/system/jobs`, and the `/actuator/health/studio` group;
  - `GET /api/v1/auth/providers` is reshaped;
  - the login request names a provider;
  - the group-mapping endpoint is renamed;
  - OpenAPI snapshot and generated types are regenerated.
- **Dependencies:**
  - new: Spring Modulith 2.1.1 (core and test starters), ArchUnit (arrives with Modulith), eslint-plugin-boundaries 7.2.0;
  - no new runtime frameworks.
- **Docs and process:**
  - ADRs 0069–0074;
  - rewrite `docs/architecture.md`;
  - update `CLAUDE.md`, `.claude/rules/20-frontend.md`, the README and the docs site;
  - add `changelog/unreleased.md` with the database-reset and endpoint-rename steps.
- **Depends on ADRs:**
  - 0002 (transport and capability model)
  - 0008 (Liquibase), partly superseded
  - 0011, 0014, 0018, 0019, 0022, 0027
  - 0034, amended
  - 0037, 0038, 0039
  - 0040, mapping model superseded
  - 0045, 0047, 0048, 0049
