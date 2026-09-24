## 1. Prerequisites (standalone commits, before the change proper)

- [x] 1.1 `security(auth)`: `SessionAuthentication.establish` rotates the session id (`request.changeSessionId()` when a session exists). Test: a pre-login session id does not authenticate after login (local and OIDC paths).
- [x] 1.2 `fix(core)`: add `NotificationDeliveryException` in `feature/alerting`, thrown by `NotificationChannelService` for a failed test notification and mapped by an alerting advice to 502 `notification-delivery-failed`. Remove the blanket `IllegalStateException` mapping from `ApiExceptionHandler`. Existing tests stay green.
- [x] 1.3 `build(deploy)!`: PostgreSQL 18.
  - Use `postgres:18` in both compose files and move the volume to `/var/lib/postgresql`.
  - Use `postgres:18-alpine` in Testcontainers.
  - Regenerate `reference-schema.sql` with pg_dump 18 and re-check the `DELIBERATE` patterns.
  - Review the tuning conf against the 18 docs (ctx7).
  - `./mvnw verify` green on 18.

## 2. ADRs

- [x] 2.1 ADR-A: plugins as runtime child contexts behind a gateway, installed from the UI into Postgres. Mark ADR-0069 "superseded in part".
- [x] 2.2 ADR-B: Module Federation for plugin UIs, with the SDK as a host singleton.
- [x] 2.3 ADR-C: a schema, pool and EntityManager factory per plugin, with no foreign keys into `public`. Amends ADR-0072.
- [x] 2.4 ADR-D: publishing the plugin API (plain jar on Central, the `exec` classifier, japicmp, npm with trusted publishing). Amends ADR-0042.
- [x] 2.5 ADR-E: the installer tier and step-up re-authentication.

## 3. Spike (throwaway, not committed)

- [x] 3.1 Backend spike, which must prove:
  - a child `GenericWebApplicationContext` under a curated parent, with `PluginInfrastructure` (`@PreAuthorize` enforced, `@Transactional` proxied)
  - a gateway to a per-plugin `DispatcherServlet`
  - `McpStatelessSyncServer.addTool`/`removeTool` with `SyncMcpAnnotationProviders`
  - per-plugin Hikari, EMF and Liquibase `CommandScope` in its own schema
  - the classloader is collected after close
  - the loader parent works under `exec.jar`

  Library APIs are confirmed through ctx7.
- [x] 3.2 Frontend spike, which must prove:
  - an MF host plus an `import:false` remote rendering a route under `clusterRoute` and a slot
  - the remote's CSS is injected
  - the public path works under `/plugin-ui/<id>/<sha8>/`
  - dev on :5173
  - built-in bundle size +5 % at most

  Record the results in design.md and delete the spike.

## 4. Settings tabs (operator-ui)

- [x] 4.1 Add an optional `group` field (`personal` | `studio` | `cluster` | `plugins`, kernel-owned and closed) to `settings.sections`, and tag the existing contributions.
- [x] 4.2 `SettingsView` becomes grouped vertical `Tabs`, with `?tab=` in the URL, keyboard operation, and focus on the panel heading.
- [x] 4.3 vitest: URL restore, keyboard, group order.

## 5. Plugin store, descriptor and validator

- [x] 5.1 Liquibase `db/changelog/kernel/plugin/`: `plugin_artifact`, `plugin_install`, `plugin_installer` and `studio_boot`, in padding order, with rollbacks. Include it in the master changelog, after security.
- [x] 5.2 `build-info` in `spring-boot-maven-plugin`, plus a `StudioVersion` reader (SNAPSHOT/IDE → unknown).
- [x] 5.3 The `plugin.json` model and its JSON Schema, published as a resource. Parse it with limits.
- [x] 5.4 `PluginValidator`:
  - `JarFile` opening and limits; entry-name rules and duplicates
  - allowlist; manifest attributes
  - confinement and overlap
  - `java.lang.classfile` checks: denied annotations and call sites, `@ConfigurationProperties` prefixes
  - namespaces, contract and range
  - Liquibase `validate`, with `runInTransaction:false` rejected

  Output is a list of violations, each with its author fix.
- [x] 5.5 Validator tests with fixture jars built in-test: one per violation, one valid jar, and a static-initialiser marker proving no code ran.
- [x] 5.6 `PluginStore`: content-addressed artifacts (keep current and previous, garbage-collect the rest), materialisation to a 0600 temp file with a sha check, and `plugin_install` state transitions.

## 6. Plugin runtime and bridges

- [x] 6.1 `@PluginApi` annotation, and the curated API context: `ClusterAccessGuard`, `ActorResolver`, `AuditService`, `BrokerCommands`, the broker read client, `SseHub`, `perm`, the settings reader, the job facade, `JsonMapper`, `Clock`, and the core-event subscription.
  - Core-event republishing (task 6.6) is not included in this slice — out of scope for this session's part.
- [x] 6.2 `PluginRuntime`: classloader (parent = the host's loader), `GenericWebApplicationContext`, `PluginInfrastructure`, per-plugin Hikari/EMF/`JpaTransactionManager`, and the proxy assertion. Close order: drain, context, pool, loader, then the `WeakReference` check.
- [x] 6.3 `PluginGateway`: `/api/v1/p/{id}/**` and `/api/v1/clusters/{clusterId}/p/{id}/**`.
  - Holds an `AtomicReference` per id and forwards to a per-plugin `DispatcherServlet`.
  - Keeps in-flight counters and drains them; answers `404 feature-disabled`, `503 plugin-updating` and `503 plugin-failed`.
  - Sets the thread context classloader (TCCL) for each call.
  - Delegates exceptions to the child's resolvers, then to the global advice.
  - springdoc excludes these paths.
- [x] 6.4 Copy-on-write dynamic registries, with built-in behaviour unchanged:
  - `FeatureRegistry` (plugin add/remove, namespaces, `PluginsChanged`, manifest version)
  - the `SettingsService` registry
  - `StreamController` topics and replays
  - `McpToolCatalog` (live reads), and the instructions line about plugins
  - job registration through `TaskScheduler` with cancellable futures
- [x] 6.5 MCP bridge: `statelessToolSpecifications` from plugin beans, `addTool`/`removeTool` (plus resources and prompts), wrapped handlers, the 190-token ceiling, and the updating error result.
- [x] 6.6 Core-event republishing into plugin contexts, with each plugin isolated when it throws.
- [x] 6.7 `PluginMigrations`:
  - a dedicated connection holding the advisory lock; `ReleaseLocks`; tag; `update`
  - the `public` relation diff (excluding partitions) and the FK-into-`public` check
  - rollback to the tag on a failed fresh activation when every change is reversible
- [x] 6.8 `PluginHost` (`SmartLifecycle`):
  - activation classes: Instant (start the new version, swap, drain the old) and Brief maintenance (503, cancel jobs, drain, migrate, start)
  - `needs-restart`; `requires` ordering and cascade disable
  - the connection budget
  - activation after `ApplicationReadyEvent` on virtual threads with a 60 s limit
  - the boot record, crash-loop safe mode and the `SAFE_MODE` switch
  - the shutdown phase and a 5 s close per plugin
- [x] 6.9 Manifest: `origin`, `version`, `vendor`, `status`, `ui.entry` and the manifest version.
- [x] 6.10 `PluginAssetController`: `/plugin-ui/<id>/<sha8>/**` served by exact entry, with content types, `nosniff` and cache headers. `icon.svg` gets `CSP: sandbox`. SPA no-fallback and an explicit `authenticated()` rule; Vite dev proxy.
- [x] 6.11 `PluginRuntimeIT`, with a fixture plugin built in-test (entity, reversible changelog, controller with `@PreAuthorize`, MCP tool, job, setting, topic):
  - install → Instant update → Brief-maintenance update → code rollback → disable → uninstall → purge
  - asserts the API, `tools/list` and `studio_help`, the schema, the pool count and the audit trail
  - the classloader is collected
  - `@PreAuthorize` is enforced

## 7. Admin API and security

- [x] 7.1 Installer tier:
  - `plugin_installer`, seeded with the bootstrap admin or `artemis-studio.plugins.initial-installers`
  - `InstallerGuard`, checked on every request
  - installer management endpoints
  - `canInstall` in the plugins view
- [x] 7.2 Step-up:
  - a session auth-time stamp
  - `POST /api/v1/auth/step-up` (password through `LoginAttemptLimiter`; end the session after 5 failures), with session id rotation
  - `403 reauthentication-required` enforcement for plugin actions (not 401, which the SPA treats as signed out)
- [x] 7.3 OIDC step-up: a resolver customizer (`prompt=login`, `max_age=300` on `?stepup`), plus a success-handler branch that checks the same provider and `sub`, and an `auth_time` within 300 s (failing closed). It stamps the session, rotates its id and returns to the stored URL.
- [x] 7.4 `PluginAdminController`:
  - `PUT /upload` (octet-stream, bounded 50 MB) → validate → pending → review DTO
  - `GET /` (inventory), `GET /{id}`
  - `POST /{id}/activate|rollback|enable|disable|uninstall`
  - `POST /{id}/purge?dryRun=`
  - `POST /check-updates` (https, no redirects, size and time limits, sha check)

  Rules: interactive only, one activation in flight, 5 uploads per hour, the kill switch.
- [x] 7.5 Review pre-flight: the contribution diff, the role impact of removed permissions, pending changesets with `update-sql`, reversibility, and the activation class.
- [x] 7.6 Audit for every step, plus a structured stdout line. Admin banner data (pending, failed, incompatible, needs-restart, safe mode).
- [x] 7.7 Security tests:
  - escalation through a custom role → 403; revocation takes effect on the next request
  - step-up stale / wrong subject / missing `auth_time` / lockout
  - bearer and MCP callers denied
  - no multipart parsing before authentication
  - rate limit
  - one test per failure-matrix row

- [x] 7.8 Self-restart (ADR-0104): `StudioRestart` (graceful exit 75, supervised by property or Kubernetes, 2-minute cool-down), `AUTOMATIC`/`MANUAL` in the plan, automatic after a confirmed restart-class activation, `POST /restart` with the ADR-0103 guards, the unload watch feeding "restart needed", compose `restart.supervised` beside `restart: unless-stopped`.

## 8. Frontend plugin host

- [x] 8.1 `@module-federation/vite` host in `vite.config.ts` only, with the shared singletons pinned exactly. `web/src/sdk/` exports the public surface, and an eslint boundary guards it.
- [x] 8.2 Async bootstrap in `main.tsx`: a splash in `index.html`, a raw manifest fetch (5 s) that seeds the query cache, then `registerRemotes` and `Promise.allSettled(loadRemote)` (10 s each), then the router. On 401 boot without plugins; `LoginView` then uses `location.replace`.
- [x] 8.3 `StudioFeature.id: FeatureId | PluginId`. Validate plugin routes (`/p/<id>`), namespace their slot ids, accept only declared topics and existing nav groups.
- [x] 8.4 Kernel catch-all routes `/p/$pluginId/$` and `/clusters/$clusterId/p/$pluginId/$`, explaining the plugin's state.
- [x] 8.5 ErrorBoundaries around plugin slots, badges and palette sources.
- [x] 8.6 Manifest version check on focus and every 60 s → non-blocking "Plugins changed — Reload".
- [x] 8.7 vitest: bootstrap paths (ok / 401 / manifest down / remote fails), catch-all states, boundaries.

## 9. Admin → Plugins screen (`web/src/features/plugins/`)

- [x] 9.1 Feature registration (`admin.tabs`) and `api.ts` hooks (polling at 1 s while activating, 30 s otherwise).
- [x] 9.2 List: `VirtualTable` rows (monogram or icon, name, vendor, version with "available", range, status in words, contributions), attention-first sorting with inline fixes, the attention summary, the connection budget.
- [x] 9.3 Drop-anywhere overlay plus `FileButton`; Check for updates; "Who can install plugins" (installers only).
- [x] 9.4 Install/update stepper:
  - Inspect: live checklist, Copy report
  - Review: capability sentences, What changes, SQL, reversibility, activation class
  - Confirm: blast radius, `ConfirmByTyping`, step-up (password or IdP)
  - Progress: timeline and four outcomes, `aria-live`, closable
- [x] 9.5 Detail drawer (`?plugin=`): Overview, Contributions, Data, History, Danger zone (disable, uninstall, purge with dry run).
- [x] 9.6 Shell-header indicator and admin banner (`shell.header` slot); empty state; disabled-with-reason for non-installers and when the kill switch is on.
- [x] 9.7 vitest for every state and outcome, and a keyboard-only pass through the stepper and the purge; contrast checked in both schemes; `npm run ui-review` screenshots.

## 10. Authoring kit and release

- [x] 10.1 `spring-boot-maven-plugin` `exec` classifier; `Dockerfile` and `ci.yml` switch to `artemis-studio-exec.jar`; image smoke test.
- [x] 10.2 Central publishing: pom `developers`/`scm`, sources, javadoc (`-Xdoclint:none`), gpg, `central-publishing-maven-plugin` (confirm the configuration with ctx7), and a CI publish step using the secrets.
- [x] 10.3 `@PluginApi` on the supported types; japicmp gate (skipped without a baseline).
- [x] 10.4 `PluginVerifier.main(jar)`, sharing `PluginValidator`, plus warnings for irreversible changesets.
- [x] 10.5 `web/packages/plugin-sdk`: `.d.ts` generated from `web/src/sdk` (without `Register`), exact peer pins, the `studioPlugin({ id })` preset (shared `import:false`, base, output, forbidden-import check); CI publish with OIDC trusted publishing.
- [x] 10.6 `examples/plugin-template/`: pom (provided dep, shade and relocate, `plugin.json` filtering, `frontend-maven-plugin`, the verifier), `web/`, and the notes sample (entity, reversible changelog, `@PreAuthorize` controller, MCP tool, route, slot, settings tab).
- [x] 10.7 CI job: build the template against the fresh Studio; Playwright e2e covering upload → review → install (route and slot live, no restart) → update with a changeset (SQL preview, 503 window) → a failed activation that keeps the old version → a broken remote that shows the catch-all.
- [x] 10.8 Compose `JAVA_OPTS` gains `-XX:MaxMetaspaceSize=256m`.

## 11. Docs and verification

- [x] 11.1 Rewrite `site/src/guide/plugins.md` as "Plugins":
  - install; the installer tier; activation classes; update and rollback; disable, uninstall and purge
  - security posture, the kill switch, the connection budget
  - troubleshooting (the failure matrix)
  - Build a plugin from the template

  Remove the in-tree and `register.patch` content, and update the sidebar.
- [x] 11.2 README, `docs/architecture.md` (plugin runtime section), `docs/dockerhub.md`, and `CLAUDE.md` (layout: the plugin runtime and where plugin code lives).
- [x] 11.3 `just verify` green; bundle size and first-load timing compared; shots diff reviewed; `openspec validate plugins --strict`.
