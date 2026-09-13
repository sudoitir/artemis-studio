## Context

See `proposal.md` (Why) for motivation. Current state:

- **Backend.** A single Maven module, package root `io.github.sudoitir.artemisstudio`, organised by layer. There is no Spring Modulith, no ArchUnit and no `package-info.java`. Cross-feature wiring already uses small interfaces in places: `BrokerEventSink`, `BrokerEventPublisher`, `RrObservationSink`, `CaptureBus.Listener`, `NotificationSender`, `AlertCondition`, `MessageTransport`. These are the natural seams.
- **Coupling hotspots:**
  - `ClusterService` injects 22 beans, including `AlertRuleRepository`.
  - `DynamicSchedules` has 18 dependencies.
  - `McpDiagnosticTools` has 16 and reads repositories directly.
  - `ScrapeScheduler` calls `AlertEvaluator` and `StreamSignals`.
  - `CaptureReconciler` and `MessageIndexCapture` depend on SQL console internals.
  - `RoutingService` writes `AuditEventRepository` directly.
  - `SettingsService.REGISTRY` is central.
- **Frontend.**
  - Code-based TanStack Router with no typed `Register`; all feature hooks live in `api/client.ts`.
  - `stream.ts` dispatches topics through an if/else chain.
  - `NAV_ITEMS`, the Spotlight groups and the Settings, Admin and Account panels are hard-coded.
  - There are 8 feature-to-feature imports, and `clusters/` and `topology/` import each other.
  - There is no import-restriction lint.
- **Schema.** 25 changesets owned by the Liquibase master. Several concerns are spread across non-adjacent files: identity in 003/014, alerting in 006/013/020, request-reply in 007/011/017/020. `audit_event` has foreign keys to `app_user`, `cluster` and `broker_node`.
- **Maintainer decisions:**
  - one Maven module;
  - per-module schema re-baseline, with no upgrade path;
  - startup-property enablement;
  - big-bang delivery with no compatibility shims;
  - change 03 parked.

## Goals / Non-Goals

**Goals:**
- A kernel that defines contracts and enforcement and never references a feature.
- Feature-first packages and folders, with the same id on both sides; boundaries fail the build.
- A small, versioned extension contract covering every contribution a feature makes today.
- Identity as an SPI, so a directory-style provider is one new module.
- Structural guarantees for audited, permission-checked, dry-runnable broker writes.

**Non-Goals:**
- Runtime plugin loading, separately shipped plugin jars, classloader isolation, micro-frontends or module federation.
- A multi-module Maven build.
- Implementing any new identity provider.
- Changing any operator-facing behaviour beyond the spec deltas in this change.
- Renaming existing operational setting keys or startup property prefixes that already match a module id.
- The Modulith event publication registry, or asynchronous cross-module events.

## Decisions

### D1. Spring Modulith + ArchUnit inside one Maven module

Modules are packages, verified by `ApplicationModules.of(ArtemisStudioApplication.class).verify()` together with ArchUnit rules in `src/test/java/.../architecture/`. Each module declares `@ApplicationModule(allowedDependencies = …)` in `package-info.java`. Modulith's default convention decides what is public: a module's **base package is its API** (types you call, SPIs you implement, event records you listen to), and every subpackage (`internal`, `web`, `mcp`) is internal to the module. A module adds a `@NamedInterface` subpackage only when it must expose a second, narrower surface.

Module detection for the nested `kernel/`, `platform/` and `feature/` packages uses `spring.modulith.detection-strategy=explicitly-annotated`, which considers only packages carrying `@ApplicationModule` (confirmed via ctx7, task 1.1).

- *Alternative:* a Maven multi-module build gives compiler-enforced boundaries, but costs about 25 poms, slower builds and duplicated Lombok, MapStruct and Spotless setup. Rejected by the maintainer.
- *Alternative:* ArchUnit alone would mean hand-writing cycle detection and internal-package rules that Modulith already provides.

### D2. Three layers, and where things go

- **Kernel** (never depends outward):
  - `kernel.core` (Branding, clock, errors, OpenAPI, SPA, mapper config);
  - `kernel.plugin` (contract, registry, manifest, feature conditions);
  - `kernel.security` (principal, grants, `@perm`, `ClusterAccessGuard`, actor, `SecretVault`, filter-chain assembly, users/roles/group mappings, identity SPI, `ScopeHierarchy` SPI);
  - `kernel.audit`, `kernel.settings`, `kernel.jobs`, `kernel.stream`.
- **Platform**:
  - `platform.broker` (Jolokia, Core pool, rate limiter, message transport, notification subscriptions, capability probe, clock skew);
  - `platform.clusters` (registration, nodes, credentials, TLS, environments, discovery, HA, split-brain, serving nodes, capability ledger, `BrokerCommands`);
  - `platform.scrape` (tiered scraper, `queue_snapshot`, `metric_sample`);
  - `platform.mcp` (server, catalogue, help, resources, prompts). This is the only optional platform module.
- **Features:** `queues`, `resources` (live views and connection control), `messages` (with DLQ), `routing`, `metrics`, `alerting` (with notification channels), `events`, `rr`, `sql` (console, message index, capture), `brokerconfig` (with config diff), `triage` (the cross-feature MCP `diagnose` and `activity_log` tools).
- **Identity providers:** `apitokens`, `identity-local`, `identity-oidc`.

**Ambiguous cases and the rationale for each:**
- **clusters + topology:** merged, because they import each other today.
- **DLQ:** part of messages, since it is message operations on dead-letter addresses.
- **sql + index + capture:** one plugin, since capture and index use the SQL parser and planner. Request-reply consumes capture through `sql::spi`.
- **brokerconfig + config-diff:** one plugin, as both answer "is this node configured right".
- **triage:** an MCP-only aggregator, allowed read-only dependencies on other features' `api`.

### D3. Extension contract v1

**Backend.** One `FeatureDescriptor` bean per module carries static facts: id, contract, title, kind, required, requires, permissions, settings, stream topics and MCP catalogue entries. Behaviour is contributed as beans of kernel SPI types: `ScheduledJob`, `BrokerEventSink`, `AlertSignalSource`, `IdentityProvider` subtypes and `HealthContributor`. Endpoints and MCP tools stay ordinary Spring annotations.

Each feature has exactly one `<Id>Feature` `@Configuration`, which carries `@ConditionalOnFeature("<id>")` and `@ComponentScan`. The meta-annotation wraps `@ConditionalOnBooleanProperty(prefix = "artemis-studio.features", name = "<id>.enabled", matchIfMissing = true)`, available in Boot 4.1 and confirmed via ctx7. `ArtemisStudioApplication` scans only `kernel` and `platform`, and `app/StudioFeatures` `@Import`s every feature configuration. That composition root is the only list of features.

- *Alternative:* auto-configuration imports per feature, rejected because auto-configurations must not be component-scanned, which fights `@ComponentScan` inside a module.
- *Alternative:* `@Profile` per feature, rejected because profiles are coarse and not self-describing in the manifest.

**Frontend.** `defineFeature({ contract: 1, id, routes, nav, palette, streamTopics, slots })` in `web/src/features/<id>/feature.ts`, with `web/src/app/features.ts` as the only list of them. A feature's `index.ts` holds only what another feature may import, so importing a public hook never loads the importing feature's whole definition, and never creates an import cycle through it.
- Routes are TanStack route objects whose parent is a kernel root from `kernel/routing/roots.ts` (`rootRoute` for pages outside a cluster, `clusterRoute` for a cluster's views). `featureView(id, View)` wraps a view in `FeatureGate`. `app/router.ts` composes every installed feature's routes with `addChildren`, enabled or not, and declares `Register`.
- A palette contribution is a component rendered inside the palette that reports its action groups, so it can use hooks.
- Slots are typed and kernel-owned: `shell.header`, `shell.navbar`, `home.empty`, `cluster.header`, `cluster.registration.afterProbe`, `queue.detail.panels`, `metrics.panels`, `topology.node.marks`, `settings.sections`, `admin.tabs`, `account.sections`.
- A cluster's layout mounts one stream subscribed to the topics of every enabled feature; a view mounts its own only for a topic no feature handles (the live events feed).

**Versioning.** One integer, declared in both `kernel.plugin` and `web/src/kernel/feature.ts`. `FeatureContractTest` and the TypeScript literal type fail on mismatch.

**Same ids on both sides.** A backend test writes `web/manifest.snapshot.json`, the pattern already used for `openapi.json` (ADR-0019). A vitest contract test asserts the frontend feature ids equal the snapshot's.

### D4. The manifest drives UI composition; the server enforces

`GET /api/v1/manifest` returns contract, features (id, title, kind, enabled, permissions, topics), `permissionCatalogue` (action, label, featureId) and `identityProviders`. `GET /api/v1/auth/providers` stays public and returns only the providers.

**Frontend behaviour:**
- The registry is filtered by enabled features.
- A disabled feature's routes render `FeatureDisabled`, which names the property.
- Permission gating stays with `useCan` (visible, disabled, with a reason).

**Backend behaviour:**
- A disabled feature's controllers do not exist.
- `ApiExceptionHandler` maps unmatched `/api/v1/**` paths whose first cluster-scoped or global segment belongs to a registered disabled feature to `404 feature-disabled`. Each descriptor declares its path prefixes for exactly this purpose.
- The SPA fallback must never answer `/api/**`.

### D5. Dependency inversions that replace today's upward calls

| Today | Becomes |
|---|---|
| `ClusterService` → `AlertRuleRepository` (built-in rules on registration) | `clusters.ClusterRegistered`, handled by alerting (`BuiltinAlertRules`) |
| Removing a cluster's rows in other modules | `ON DELETE CASCADE` foreign keys onto `cluster`, each along an allowed dependency edge (`SchemaOwnershipTest`); no removal event is needed |
| `ClusterService` → Core subscriptions and pools | `BrokerSessions.release(clusterId)`, a clusters → broker call along the allowed edge |
| `ScrapeScheduler` → `AlertEvaluator` | `scrape::events.ScrapeTierCompleted(clusterId, tier)`, handled synchronously by alerting, so ordering is unchanged |
| `BrokerConnections` → cluster, credential and TLS repositories | `broker::spi.ConnectionSettingsSource`, implemented by clusters |
| `PermissionResolver` → `ClusterEnvironmentIndex` | `security::spi.ScopeHierarchy`, implemented by clusters |
| `SettingsService` → `BrokerClientFactory.setTimeouts` | `SettingDef.apply`, declared by broker |
| `BrokerConfigDriftService` → alert `CONFIG_DRIFT` | `alerting::spi.AlertSignalSource`, implemented by brokerconfig |
| Capture sinks for index and request-reply | `sql.CaptureBus.Listener`; rr's `CaptureRrSink` is `@ConditionalOnFeature("sql")`, so rr runs without sql |
| Frontend `RegisterCluster` → `brokerconfig/RecommendedConfiguration` | slot `cluster.registration.afterProbe` |
| Frontend `QueueDetailDrawer` → metrics charts | slot `queue.detail.panels` |
| Frontend `MetricsView` → `rr/LatencyPanel` | slot `metrics.panels` |
| Frontend `SettingsView` / `AdminView` / `AccountView` imports | slots `settings.sections`, `admin.tabs`, `account.sections` |
| Frontend `RootLayout` → firing counts, cluster rail | slots `shell.header`, `shell.navbar` |
| Frontend `ClusterLayout` / `HomeView` → cluster detail, register form | slots `cluster.header`, `home.empty` |
| Frontend `TopologyGraph` → firing alerts | slot `topology.node.marks` |
| Frontend `CommandPalette` → clusters and queues | palette contributions |

The remaining allowed feature → feature edges on the backend (`api` named interfaces):
- brokerconfig → queues, routing;
- sql → messages, queues, routing;
- triage → queues, alerting, rr, events, metrics;
- every feature → clusters (platform).

On the frontend (another feature's `index.ts` only):
- rr → queues (`AddressPicker`, `useQueues`);
- sql → messages (`MessageDetailPanel`), queues (`useQueues`);
- brokerconfig → messages (`useDlq`, to suggest dead-letter addresses; absent when messages is disabled);
- audit → security (`useUsers`, for the user filter); apitokens → security (`usePermissionsCatalogue`);
- every feature → clusters (`useCluster`, `useClusters`, `useTopology`, `CapabilityLedger`).

### D6. Boundary rules

Enforced by ArchUnit and Modulith:
- Features may use only `@PreAuthorize` from Spring Security. Identity-provider modules may use what their SPI needs, and only redirect providers see `HttpSecurity`.
- `@Entity`, repositories, `JdbcTemplate` and `EntityManager` are used only inside the owning module's `internal.persistence`.
- Jolokia and Core client types stay internal to `platform.broker`. Cluster-wide broker writes go through `BrokerCommands` (D8).
- `SseEmitter` appears only in `kernel.stream` and in a module's own per-request stream controller (today only the SQL tail). A kernel wrapper for one consumer is not worth its indirection.
- `@Scheduled`, `TaskScheduler` and `SchedulingConfigurer` appear only in `kernel.jobs` and `platform.scrape`.
- No `ApplicationContext` or `BeanFactory` injection and no `@Enable*` in features.
- Cross-module foreign keys follow allowed edges (`SchemaOwnershipTest`).

**Frontend:** `eslint-plugin-boundaries` 7.2.0 with `boundaries/dependencies` and `default: "disallow"`:
- `ui` imports nothing app-specific except the generated DTO types (`kernel/api/schema.d.ts`);
- `kernel` → `ui`;
- `feature` → `kernel`, `ui`, itself, and allowed features' `index.ts`;
- `app` → all;
- a test beside its code may also use the shared harness in `test/`.

### D7. Security and the identity SPI

A sealed `IdentityProvider` in `kernel.security` has three shapes:
- **`CredentialIdentityProvider`**: `authenticate(username, password)` returns the principal or nothing.
- **`RedirectIdentityProvider`**: `startPath`.
- **`BearerIdentityProvider`**: `authenticate(token)` returns the principal or nothing.

A module contributes providers through one `IdentityProviders` bean, whose `configure(HttpSecurity)` only redirect sign-in uses. The kernel's login path dispatches to the named credential provider (`local` when omitted), throttles, and audits through an `AuthenticationAudit` SPI the audit module implements; one bearer filter asks the bearer providers. An unconfigured provider fails like a wrong password.

External providers funnel into `IdentityProvisioner.provision(ExternalIdentity(providerId, subject, username, email, groups))`. This generalises ADR-0040: users are keyed by `(provider_id, external_subject)`, and `identity_group_mapping(provider_id, group_name, role_id, scope…)` is re-applied at every login.

The permission catalogue is the union of `descriptor.permissions`. `PermissionCatalogueTest` checks that every literal used in `@PreAuthorize`, `requireCluster`, `LifecycleKind` and frontend `can('…')` exists, and that built-in role seeds are a subset of the catalogue plus wildcards.

**Future directory provider:**
1. Add a new `feature/identityldap` module of kind `IDENTITY_PROVIDER`.
2. Wrap Spring Security's `LdapAuthenticationProvider` in a `CredentialIdentityProvider`.
3. Add one line in `StudioFeatures`.

No kernel, feature or frontend change is needed.

- *Alternative:* expose `Customizer<HttpSecurity>` to every provider, rejected because it gives modules unrestricted framework access.

### D8. Broker writes through `BrokerCommands`

`BrokerCommands.run(Command)` in `platform.clusters` — beside the serving topology and capability ledger it needs — executes one fixed sequence for every cluster-wide broker write:
1. `requireCluster`;
2. one target per logical node, liveness polled;
3. `audit.begin` in the caller's transaction, before any broker call;
4. per-node estimate;
5. dry run → `WOULD_APPLY`, stating any estimate that could not be made;
6. cap check (audited 422 refusal);
7. rate-limited fan-out, a node's failure being its outcome;
8. `audit.finish` with per-node detail;
9. topic signal after commit.

It returns the per-node `LifecycleOutcome`. `noRollbackFor` lives on the executor.

Queue, address and divert lifecycle and the address-scoped consumer close run through it. Message operations and node-scoped closes (one named node), the configuration apply (canary, verify, halt, step cap, hazard acknowledgement) and capture's divert reconciliation keep their own sequences; each still writes its audit row before the broker call. `RoutingService` reads divert ownership through `AuditService.history`. Non-broker mutations keep calling `AuditService`. `AuditCoverageTest` asserts every public mutating service method in a feature goes through one of the two.

- *Alternative:* AOP-based auditing, rejected because it hides ordering. The audit row must exist before the broker call, and it records per-node detail that an aspect cannot see.

### D9. Jobs, health and lifecycle

- **Jobs.** `kernel.jobs` generalises `DynamicSchedules`/`DynamicTriggers` into a registry of `ScheduledJob(id, featureId, Schedule, Runnable)`, where `Schedule` is either a fixed-delay supplier or a cron supplier read from settings. It records `JobStatus` and a Micrometer timer `studio.job{job,feature}`. `ScrapeScheduler` keeps its own pool (ADR-0048) but reports `JobStatus` through the same API.
- **Health.** Contributors `jobs`, `brokers` and `subscriptions` are registered under a `studio` group that is excluded from liveness and readiness. `GET /api/v1/system/jobs` requires `settings:read`.
- **Shutdown** order via `SmartLifecycle` phases: stream → jobs and scrape → subscriptions → Core pool → HTTP clients.

### D10. Schema ownership and the re-baseline

Layout:
- `db.changelog-master.xml` includes module changelogs in topological order: kernel core, security, audit, settings; then clusters, scrape, apitokens, events, alerting, rr, sql, brokerconfig.
- Each `<module>/changelog.xml` uses `includeAll path="changes/" relativeToChangelogFile="true"`, which runs alphabetically (confirmed via ctx7).

**Baselines** are produced by migrating an empty Testcontainers database with the current changelog, dumping schema plus seed data, splitting it by owner, and hand-checking:
- column order (non-negotiable #7);
- storage parameters;
- partitions and their defaults;
- full-text configuration and seeds.

Each baseline carries a rollback.

**Deliberate differences**, each asserted by a schema-diff test:
- `oidc_role_mapping` → `identity_group_mapping` with `provider_id` and a per-provider default role;
- `app_user` gains `provider_id` and `external_subject` (unique together);
- `audit_event` loses its three foreign keys and gains `cluster_name`.

Disabled features still migrate. Settings keys and existing `artemis-studio.*` prefixes stay as they are wherever they already match a module id. Renamed prefixes are listed in `changelog/unreleased.md`.

## Risks / Trade-offs

- **[Existing installs cannot upgrade]** → The change is marked breaking in the commit and `changelog/unreleased.md` gives explicit reset steps. Studio is pre-stable on the `:dev` channel with no stable tag.
- **[A big-bang diff is hard to review and bisect]** → Work lands in commit groups that each pass `just verify` (tasks §2–§10). Pure moves are committed separately from behaviour-bearing edits.
- **[Hand-splitting the baseline silently changes the schema]** → A schema-diff test compares the new baseline with a database migrated by the old changelog, and fails on any difference beyond the listed deliberate ones. `ddl-auto=validate` also runs.
- **[Modulith detection of nested module packages does not behave as expected]** → Confirmed in the first task, before any moves. The fallback is to flatten module packages directly under the root with a `kernel`/`platform`/`feature` naming prefix, with no contract change.
- **[`BrokerCommands` changes an audit or cap edge case]** → The existing dry-run, bulk-cap and MCP dry-run integration tests run unchanged against the executor before any feature moves.
- **[Synchronous events hide coupling]** → Event records live in named `events` interfaces, so Modulith records them as dependencies, and the listener set is documented by the generated module canvases.
- **[Disabled-feature 404 mapping mis-attributes a path]** → Descriptors declare path prefixes, and `FeatureToggleTest` exercises every optional feature's endpoints disabled.
- **[Frontend move breaks tests relying on relative imports]** → Move each folder with its tests in the same commit, and make `npm test` a gate per group.
- **[Change 03 drifts while parked]** → Its tasks are rewritten against the new layout as the first follow-up; it has 0 of 45 tasks done, so nothing needs porting.

## Migration Plan

1. The branch lands as one merge to `main`, which cuts one CalVer release. The commit set includes `!`-marked commits, and `changelog/unreleased.md` states:
   - stop Studio, drop or recreate the Postgres database (for Compose, remove the volume), then start the new image;
   - re-register clusters, users, roles, tokens, notification channels, alert rules, capture subscriptions and broker configuration declarations;
   - the group-mapping endpoint and any renamed property prefixes.
2. **Rollback:** run the previous image tag against a database restored from a pre-upgrade backup. A database already migrated by the new baseline is not readable by the old image.

## Open Questions

- **Nav group labels** (Observe, Messaging, Resources, Configuration, Activity) are user-visible copy and may be reworded during implementation; the specs fix the grouping behaviour and the order, not the wording.
- **Export/import of configuration rows** across the reset is not planned. It can be added as a separate change if operators ask for it before a stable release.
