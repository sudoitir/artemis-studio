# Tasks

Each group ends with `just verify` green and is committed on its own. Pure moves are committed separately from behaviour-bearing edits. See `design.md` for D1–D10.

## 1. Groundwork

- [x] 1.1 Confirm via ctx7 which Spring Modulith 2.1 setting detects explicitly annotated modules in nested `kernel/`, `platform/` and `feature/` packages. Record the answer in design D1, or apply its fallback.
- [x] 1.2 Write ADR-0069 (kernel + plugin modular monolith, Modulith, build-time composition, startup toggles) and add its index row.
- [x] 1.3 Write ADR-0070 (extension contract v1, manifest, slots, closed nav-group catalogue; amends ADR-0034).
- [x] 1.4 Write ADR-0071 (broker writes only through `BrokerCommands`; formalises ADR-0022 and ADR-0049).
- [x] 1.5 Write ADR-0072 (per-module schema ownership and one-time re-baseline; supersedes the history-continuity part of ADR-0008; audit foreign keys removed). Mark ADR-0008 accordingly.
- [x] 1.6 Write ADR-0073 (identity provider SPI and provider-keyed provisioning; supersedes ADR-0040's mapping model). Mark ADR-0040 accordingly.
- [x] 1.7 Write ADR-0074 (frontend module boundaries with eslint-plugin-boundaries).
- [x] 1.8 Add the Spring Modulith 2.1.1 BOM, `spring-modulith-api` (annotations only) and `spring-modulith-starter-test` to `pom.xml`, and `eslint-plugin-boundaries@7.2.0` to `web/package.json`.
- [x] 1.9 Add `architecture/ModularityTest` in report mode, writing the current violation list to the build output. Add the boundaries rule to `web/eslint.config.js` at `warn`.
- [x] 1.10 Add a note to `openspec/changes/03-message-replay-from-payload/proposal.md` that it is parked behind this change.

## 2. Kernel contract and composition root

- [x] 2.1 Create `kernel/plugin` with `api/` holding `Contract` (VERSION = 1), `FeatureDescriptor`, `PermissionDef`, `SettingDef`, `TopicDef`, `McpToolDef`, the `Kind` enum and the `@ConditionalOnFeature` meta-annotation. Add a `package-info.java` for each kernel module.
- [x] 2.2 Implement `FeatureRegistry`, which collects descriptors and refuses startup when:
  - a required module is disabled;
  - an enabled feature requires a disabled one;
  - a contribution is declared twice;
  - a contract version does not match.
- [x] 2.3 Add `app/StudioFeatures` (composition root) and restrict `ArtemisStudioApplication` scanning to `kernel` and `platform`.
- [x] 2.4 Add `GET /api/v1/manifest` and a controller test covering enabled/disabled features, permission attribution and `401` when unauthenticated.
- [x] 2.5 Add `FeatureContractTest` (contract mismatch fails, duplicate contributions fail) and `ManifestSnapshotTest`, which writes `web/manifest.snapshot.json`.
- [x] 2.6 Map unmatched `/api/v1/**` paths under a disabled feature's declared prefixes to a `404` problem detail of type `feature-disabled` naming the property. Ensure the SPA fallback never answers `/api/**`.

## 3. Kernel modules

- [x] 3.1 Move `Branding`, clock, `TimeController`, `StudioInstance`, errors (`ApiExceptionHandler`, `NotFoundException`, `ConflictException`, `Attempt`), `OpenApiConfig`, `SpaRoutingConfig` and `CentralMapperConfig` into `kernel/core`.
- [x] 3.2 Move security into `kernel/security`: principal, grants, `PermissionResolver`, `ClusterAccessGuard`, `ActorResolver`, `SecretVault`, `SecurityConfig`, CSRF, and the user/role/grant services and persistence. Introduce the `spi.ScopeHierarchy` interface.
- [x] 3.3 Assemble the permission catalogue from descriptors, replacing the static `Permissions.catalogue()`. Keep permission string constants in the owning modules' `api`.
- [x] 3.4 Add `PermissionCatalogueTest`. It checks every permission literal in `@PreAuthorize`, `requireCluster`, `LifecycleKind` and frontend `can('…')` calls against the catalogue, and checks that built-in role seeds ⊆ catalogue ∪ wildcards.
- [ ] 3.5 Move `AuditService`, the audit entity, repository, query service and controller into `kernel/audit`. Record `cluster_name` on each audit event. The `cluster_name` column lands with the baseline in 7.3.
- [x] 3.6 Move the settings plane A registry into `kernel/settings`, assembled from `SettingDef` contributions, with keys unchanged. Move the plane B JDBC property source (bootstrap) there as well.
- [x] 3.7 Refuse writes and resets of settings owned by disabled features with a `404` problem detail and no audit change. Omit those settings from the settings read. Keep stored values.
- [x] 3.8 Create `kernel/jobs`: a `ScheduledJob` SPI and a generalised `DynamicSchedules`/`DynamicTriggers` that record `JobStatus` and the `studio.job{job,feature}` timer.
- [x] 3.9 Create `kernel/stream`: `SseHub` (the publishing API), `StreamController`, a topic registry built from the enabled descriptors' `TopicDef`s (unknown or disabled topics ignored), and an `EventReplay` SPI for `Last-Event-ID` replay.
- [x] 3.10 Split `ArtemisStudioProperties` into per-module `@ConfigurationProperties` records, keeping every existing prefix. Move `rr.clock-skew-tolerance-ms` to `broker`, drop the unread `branding.product-name` and `security.session-timeout` keys, and list both changes in a draft `changelog/unreleased.md`.

## 4. Platform modules

- [x] 4.1 Create `platform/broker` and move the Jolokia client and factory, `NodeCallLimiter`, `ManagementRefusal`, `BrokerMBeans`, `BrokerXmlSnippets`, `BrokerTime`, clock offset, Core pool and connection factory, `MessageTransport` and both implementations, notification subscription classes, `CapabilityProbe`, `BrokerListOps` and `QueueRow`. Expose `api`/`spi` named interfaces only.
- [x] 4.2 Introduce `broker::spi.ConnectionSettingsSource` and remove the broker's direct use of cluster, credential and TLS repositories.
- [x] 4.3 Declare the broker's settings, including the connect/read timeout `SettingDef.apply` that replaces the `SettingsService → BrokerClientFactory` call.
- [x] 4.4 Implement `BrokerCommands.run(Command)` in `platform.clusters`, returning the per-node `LifecycleOutcome` (moved beside it), following design D8. Carry `noRollbackFor` on the executor.
- [x] 4.5 Migrate the cluster-wide writes onto `BrokerCommands`: `QueueLifecycleService` (queue, address and divert lifecycle) and `ConnectionControlService`'s address-scoped close. Move `RoutingService`'s divert-ownership read from `AuditEventRepository` to `AuditService.history`. Message operations, node-scoped closes, the configuration apply and capture reconciliation keep their own audited sequences (design D8). Existing dry-run, bulk-cap and MCP dry-run tests pass unchanged.
- [x] 4.6 Create `platform/clusters`: registration, nodes, credentials, TLS, environments, `TopologyDiscovery`, `HaStateEvaluator`, `SplitBrainRegistry`, `ServingNodes`, `CapabilityLedger`, `ClusterLock`, with controllers and mappers. Implement `ScopeHierarchy` and `ConnectionSettingsSource`.
- [x] 4.7 Publish `ClusterRegistered` inside the registration transaction; alerting seeds its built-in rules from it. Release a removed cluster's Core connections and subscriptions through the broker's `BrokerSessions`, so `ClusterService` no longer depends on alerting or Core pool internals. Removal events are added with the first module that must react to one (7.3 drops the cross-module foreign keys that clean up today).
- [x] 4.8 Create `platform/scrape`: `ScrapeScheduler`, `ScrapeCycle`, `ScrapePersistence`, `SweepCursor`, the queue snapshot and metric sample writer, partition maintainer and reaper, and `StreamSignals`. Expose the `QueueSnapshots` and `MetricSeries` read APIs.
- [x] 4.9 Publish `ScrapeTierCompleted` in place of the direct `AlertEvaluator` call, preserving evaluation order. Report scrape tiers through `JobStatus`.
- [x] 4.10 Create `platform/mcp`: server config, a tool catalogue assembled from `McpToolDef`, `studio_help`, catalogue resources, runbook prompts, `McpErrors`, `McpArgs` and instructions. Mark it optional; when disabled, `/mcp` does not exist.
- [x] 4.11 Add `AuditCoverageTest`: every public mutating service method in a feature goes through `BrokerCommands` or `AuditService`.

## 5. Feature modules (backend)

Each task below does the same four things:
1. move the classes into `feature/<id>/` with `api`, `web`, `mcp` and `internal/*` packages;
2. add `<Id>Feature` and the descriptor;
3. add `package-info` allowed dependencies;
4. move the feature's tests to mirror the new packages.

- [ ] 5.1 `events`: broker event writer, entity, repository, reaper, stream publisher, service and controller. It registers a `BrokerEventSink` and owns the `events` topic.
- [ ] 5.2 `metrics`: `MetricQueryService` and `MetricsController`, reading `scrape::api`. MCP tool `metric_series`.
- [ ] 5.3 `resources`: split `ClusterResourceController`; move `PagedListService`, `CrossNodeAggregator`, `ResourceKind`/`Query`, `ResourceViewMapper` and connection control. Topics `consumers`, `sessions`, `connections`. MCP tools `list_resources`, `connection_action`.
- [ ] 5.4 `queues`: queue lifecycle service, operations and controller, `LifecycleKind`/`Outcome`, queue and address listing endpoints, `QueueViewMapper`. MCP tool `queue_lifecycle`.
- [ ] 5.5 `routing`: diverts, bridges, `RoutingService` and `RoutingController`.
- [ ] 5.6 `messages`: message service, operations and controller, `MessageAction`, DLQ service and controller. MCP tools `message_action`, `send_message`, `browse_messages`.
- [ ] 5.7 `alerting`: alerting domain, evaluator (listening to `ScrapeTierCompleted`), rule and alert services, notification channels, dispatcher, backoff, senders and controllers. Define `spi.AlertSignalSource`; handle `ClusterRemoving`. Topic `alerts`, MCP tool `alert_rule`.
- [ ] 5.8 `rr`: resolvers, sampler, correlator, metrics, notification observer, deadline sweep, domain, persistence and controller. Its capture listener loads only when `sql` is enabled. Topic `rr`, MCP tool `trace_request_reply`.
- [ ] 5.9 `sql`: console, index, capture tap/consumer/bus/reconciler, index writer and partitions, controllers, `SqlQueryTickets` and the per-request tail stream. Define `spi.CaptureListener`.
- [ ] 5.10 `brokerconfig`: operations, domain, config diff, `ConfigReader`, services, persistence and controllers. Implement `AlertSignalSource` for drift. Topic `config`; MCP tools `broker_config`, `config_diff`, `broker_config_change`.
- [x] 5.11 `triage`: split `diagnose` and `activity_log` out of `McpDiagnosticTools`, and delete `McpDiagnosticTools`/`McpTuningTools` once every tool lives in its feature.
- [ ] 5.12 Declare `ScheduledJob` beans for every former `DynamicSchedules` task in its owning module, and delete the central task list.
- [ ] 5.13 Switch `ModularityTest` to failing and add `BoundaryRulesTest` (design D6).
- [ ] 5.14 Add an `@ApplicationModuleTest` per feature bootstrapping direct dependencies only.
- [ ] 5.15 Add `FeatureToggleTest`. For each optional feature, the context starts with it disabled, and:
  - its endpoints return `404 feature-disabled`;
  - its jobs never run;
  - its tools, topics, settings and permissions are absent;
  - runbook prompts state the feature is disabled.

## 6. Identity provider SPI

- [x] 6.1 Add the sealed `IdentityProvider` SPI (`Credential`, `Redirect`, `Bearer`) and the `IdentityProviders` contribution in `kernel/security`, with an `AuthenticationAudit` SPI the audit module implements. `ExternalIdentity` and `IdentityProvisioner` land with provider-keyed provisioning in 6.6.
- [x] 6.2 Assemble the chain from providers: the login path dispatches to the named credential provider; one bearer filter delegates to bearer providers; each contribution's `configure(HttpSecurity)` adds what redirect sign-in needs.
- [x] 6.3 Make `POST /api/v1/auth/login` accept `provider` (local when omitted). An unconfigured provider fails like a wrong password.
- [x] 6.4 Make `feature/identitylocal` the `local` credential provider with password change and `AdminBootstrap`; `LoginAttemptLimiter` and `MustChangePasswordFilter` move to the kernel's session path, since they apply to every sign-in.
- [x] 6.5 Make `feature/apitokens` a bearer identity provider with its token service and controller; the MCP authorization tests pass unchanged.
- [ ] 6.6 Create `feature/identityoidc`: `RedirectIdentityProvider` and claim extraction feeding `IdentityProvisioner`. Provisioning is keyed by provider and subject.
- [ ] 6.7 Replace `/api/v1/oidc/mappings` with `/api/v1/identity/providers/{providerId}/group-mappings`, with a per-provider default role. The old path returns `404`.
- [x] 6.8 Return `identityProviders` from `GET /api/v1/auth/providers` (public) and from the manifest.
- [ ] 6.9 Add a test that registers a fake `CredentialIdentityProvider` from test configuration only. It proves login, JIT provisioning, per-provider group mapping and separate accounts for the same subject across providers, with no kernel edit.

## 7. Schema re-baseline

- [x] 7.1 Migrate an empty Testcontainers database with the current changelog and dump schema plus seed data as the reference.
- [x] 7.2 Write `db/changelog/<module>/changelog.xml` (`includeAll` over `changes/`) and `changes/0001-baseline.sql` for kernel core, security, audit and settings, and for clusters, scrape, apitokens, events, alerting, rr, sql and brokerconfig. Preserve column order, storage parameters, partitions, full-text configuration and seeds; give each a rollback.
- [ ] 7.3 Apply the deliberate differences:
  - `identity_group_mapping` with `provider_id` and a per-provider default role;
  - `app_user.provider_id` and `external_subject`, unique together;
  - `audit_event` without foreign keys and with `cluster_name`.
- [x] 7.4 Rewrite `db.changelog-master.xml` to include module changelogs in topological order, and delete `changes/001–025`.
- [ ] 7.5 Move each `@Entity` and repository into its owning module's `internal.persistence`.
- [ ] 7.6 Add `SchemaOwnershipTest`:
  - every table is created by exactly one module;
  - entity tables live in the owning module;
  - foreign keys follow allowed edges;
  - `ddl-auto=validate` passes.
- [ ] 7.7 Add a schema-diff test comparing the baseline with the reference dump from 7.1. The only permitted differences are the ones listed in 7.3.
- [ ] 7.8 Make the audit read for a removed cluster available to globally granted callers. Test that removing a cluster or user leaves its audit events unchanged.

## 8. Operational health

- [x] 8.1 Add `GET /api/v1/system/jobs` (requires `settings:read`) and a `jobs` health contributor that reports a job degraded after three missed intervals.
- [x] 8.2 Add a `brokers` health contributor with, per node, last success/failure and rate-limit wait, and per cluster the open Core connection count (pooled-jms reports open connections only, with no active/idle split).
- [x] 8.3 Add a `subscriptions` health contributor with, per serving node, whether the subscription is established and, if not, why.
- [x] 8.4 Configure the `studio` health group and exclude it from liveness and readiness. Test that an unreachable cluster leaves readiness up.
- [x] 8.5 Order shutdown with `SmartLifecycle` phases (stream → broker calls and scrape → subscriptions and capture → Core pool); management clients are built per call and hold nothing to close. Test the phase order and that no broker call starts once the gate has closed.

## 9. Frontend kernel

- [ ] 9.1 Create `web/src/kernel/feature.ts`: `StudioFeature` with contract `1`, `defineFeature`, route factories, nav, palette, stream topic handler and typed slot contracts.
- [ ] 9.2 Create `kernel/registry.ts` and `kernel/manifest.ts`, with a `useManifest` query and a `useFeature(id)` hook that filters out disabled features.
- [ ] 9.3 Create `kernel/slots.tsx` with `useSlot(name)`, ordered and filtered by enabled features.
- [ ] 9.4 Create `kernel/nav/groups.ts` (observe, messaging, resources, configuration, activity) and a grouped rail. Groups have heading semantics when expanded, stay separated when collapsed, and a group with no views is hidden.
- [ ] 9.5 Create `kernel/routing/roots.ts` (`rootRoute`, `clusterRoute`, `adminRoute`) and `app/router.ts`, which composes feature routes and declares `Register`. Move search validators into their feature.
- [ ] 9.6 Create `kernel/shell/FeatureDisabled`: states the feature is disabled, names the property, links back to the cluster.
- [ ] 9.7 Split `api/client.ts` into `kernel/api` (request, `ApiError`, polling, key roots, generated schema) and per-feature `api.ts`. Remove the kernel's back-edges to `app/useDismissedNotice` and `app/time`.
- [ ] 9.8 Replace the `stream.ts` topic if/else chain with a registry of feature-contributed topic handlers.
- [ ] 9.9 Move `ConfirmByTyping`, `NodeOutcomeSummary`, `CapabilityGate`, `VirtualTable` and `Pager` into `web/src/ui/`.
- [ ] 9.10 Rebuild `RootLayout`, `ClusterLayout`, `HomeView`, `AdminView`, `AccountView`, `SettingsView`, `UserMenu` and `CommandPalette` as kernel shells that render contributions and slots.
- [ ] 9.11 Build `LoginView` from `/auth/providers`: a credential form, a provider choice when there is more than one credential provider, and one action per redirect provider.
- [ ] 9.12 Add a router-aware render helper to `test/render.tsx`, plus manifest fixtures.
- [ ] 9.13 Add kernel tests: registry filtering, slot ordering, topic dispatch, grouped-nav headings and collapsed accessible names, `FeatureDisabled`, and login from providers. Query by role and name.

## 10. Frontend features

Each task moves the folder to `features/<id>/` with its tests, adds `index.ts` with `defineFeature` and its public exports, and declares routes, nav with group and order, topics and slots.

- [ ] 10.1 `clusters` (merging `clusters/` and `topology/`): rail entries, register, topology, `CapabilityLedger` export, environments admin tab, credentials settings section, and the `home.empty` and `cluster.header` slots.
- [ ] 10.2 `queues` (`AddressPicker` export) and `metrics` (charts contributed to `queue.detail.panels`).
- [ ] 10.3 `resources`, `routing` and `events`.
- [ ] 10.4 `messages` (with `dlq/`, `MessageDetailPanel` export).
- [ ] 10.5 `alerting` (firing badge through the nav contribution, notification channels settings section).
- [ ] 10.6 `rr` (`LatencyPanel` into `metrics.panels`).
- [ ] 10.7 `sql` (index subscriptions settings section).
- [ ] 10.8 `brokerconfig` (with `config/`, `RecommendedConfiguration` into `cluster.registration.afterProbe`).
- [ ] 10.9 `identity` (users, roles, provider group-mapping admin tabs); `identity-local` (change password route, account section); `apitokens` (account API keys section); `mcp` (account connection section); `audit`; `settings` (operational configuration and display preferences).
- [ ] 10.10 Regenerate `web/openapi.json` and `schema.d.ts`, and update every call site of the group-mapping endpoint and login request.
- [ ] 10.11 Add `FeatureIdsContractTest`: frontend feature ids equal `web/manifest.snapshot.json`.
- [ ] 10.12 Switch eslint boundaries to `error`, allowing exactly the feature edges in design D5.

## 11. Documentation and release notes

- [ ] 11.1 Add a `DocumentationTest` that writes Spring Modulith module canvases and diagrams to `docs/modules/`.
- [ ] 11.2 Rewrite `docs/architecture.md` for kernel, platform and features: contract, dependency graph, broker command path, identity SPI, schema ownership.
- [ ] 11.3 Update `CLAUDE.md`:
  - package layout;
  - where a new feature goes;
  - the re-baseline note beside non-negotiable #7.
- [ ] 11.4 Update `.claude/rules/20-frontend.md`:
  - slots, nav groups and boundaries;
  - `ui/` in place of `shared/`;
  - reuse paths.
- [ ] 11.5 Update `README.md`, `site/` guide pages and `docs/dockerhub.md` where they describe run instructions or layout.
- [ ] 11.6 Finalise `changelog/unreleased.md`:
  - database reset steps and what to re-register;
  - group-mapping endpoint rename;
  - login request provider field;
  - renamed property prefixes.
- [ ] 11.7 Run `just verify` and `just dev-up`, then check each of the following:
  - login;
  - grouped rail;
  - queue browse;
  - dry-run purge through confirmation to the per-node result, with its audit row;
  - live stream updates;
  - SQL query;
  - configuration apply dry run;
  - MCP `studio_help` with a token.
- [ ] 11.8 Restart with the SQL feature disabled and check:
  - SQL is absent from the rail;
  - its deep link shows `FeatureDisabled`;
  - its API returns `404 feature-disabled`;
  - `studio_help` omits SQL tools;
  - `/actuator/health/studio` reports jobs, brokers and subscriptions.
