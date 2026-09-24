## Context

Studio is a single-instance modular monolith. Built-in features are composed at build time: `StudioFeatures` on the backend and `web/src/app/features.ts` on the frontend (ADR-0069, ADR-0070). Several registries are fixed at startup:
- `FeatureRegistry`
- the `SettingsService` registry
- `StreamController`'s topics
- `McpToolCatalog`
- the job scheduler

The router is built before any manifest fetch. The MCP server is STATELESS (`McpStatelessSyncServer`). Sessions are stored in JDBC and are 8 h long, and grants are loaded once at sign-in. Local login does not rotate the session id. Postgres is 17 at the start of this change and moves to 18 before it (a separate commit). See proposal.md for why.

Two adversarial reviews shaped this design. Their findings are referenced as [R1-x] and [R2-x] in the approved plan.

## Goals / Non-Goals

**Goals**
- A third party can add backend, data and UI to a running Studio from the UI alone.
- Plugins activate with no restart whenever that is safe.
- Nothing a plugin does can stop Studio or break a built-in feature.

**Non-Goals**
- Multi-instance Studio: it is single-instance by ADR-0037, ADR-0039 and ADR-0093. Advisory locks only guard an overlapping deploy.
- A plugin marketplace or catalogue.
- Background update checks.
- Plugin identity providers.
- Sandboxing hostile code. The JVM has had no sandbox since SecurityManager was removed, and plugins are trusted in-process code.
- Operator rollback of applied changesets.
- Converting built-ins into plugins.

## Decisions

1. **One Spring child context and one classloader per plugin, parented by a curated API context.**
   - The alternative was one flat classloader in the main context, which the first review rejected [R1-B1..B5]. There, plugin beans could override or post-process core beans, a plugin's `SpringLiquibase` switched off core migrations, entities joined the core persistence unit, and nothing could unload.
   - A child context isolates bean overriding, post-processors and `@Primary`, gives per-plugin JPA, and makes unload possible.
   - The curated parent means a plugin can only inject `@PluginApi` beans.
2. **An HTTP gateway forwarding to a per-plugin `DispatcherServlet`,** not `registerMapping` into the main handler mapping [R2-B1].
   - The main MVC caches are keyed by `Class` and would pin old classloaders.
   - Registering the same mapping twice is ambiguous, so a swap would need a 404 window.
   - The gateway swaps an `AtomicReference`, drains in-flight requests, and owns the `503`/`404` answers.
3. **`PluginInfrastructure` imported into every child** (transactions, method security, validation, web MVC, request/session scopes), plus an activation assertion that annotated beans are proxied [R2-B2]. The alternative, relying on the plugin to configure these itself, fails silently.
4. **A schema, a Hikari pool (3 connections, `search_path` in the init SQL) and an EntityManager factory per plugin; no foreign keys into `public`** [R2-B3, R2-B4].
   - A shared pool would leak Liquibase's `SET SEARCH_PATH` into core connections.
   - Foreign keys into `public` would let a plugin block cluster or user deletion and core migrations.
5. **Liquibase via `CommandScope`, run by the runtime under a per-plugin advisory lock,** never through a `SpringLiquibase` bean, which backs off Boot's core Liquibase [R1-B1].
6. **Validation reads bytecode through the JDK `java.lang.classfile` API and opens the jar with `JarFile`,** the same parser the loader uses [R2-M7]. No plugin code runs during inspection. The alternative, `Class.forName(…, false, …)`, still defines classes and links them.
7. **Artifacts live in Postgres** (`bytea STORAGE EXTERNAL`, content-addressed, current and previous version kept). The alternatives were a mounted directory, which the user removed, and large objects, which need `vacuumlo` and extra permissions.
8. **Activation classes: Instant, Brief maintenance, Restart.** They are computed from the pending changesets (Liquibase `status`) and the descriptor, and shown before confirmation.
9. **Safe mode, not fail-fast.** Because the UI is the only management path, a plugin failure must never stop Studio. Activation happens after `ApplicationReadyEvent` with a 60 s limit, and a boot record detects crash loops [R2-M2, R2-M3].
10. **An installer tier in its own table, checked on every request,** in place of a role permission [R2-B5]. Roles are free-form strings that `user:admin` can edit, and grants are cached in an 8 h session. Seeded with the bootstrap administrator, which makes install on by default.
11. **Step-up re-authentication.**
    - Local users re-enter their password through `LoginAttemptLimiter`, and the session is ended after 5 failures.
    - OIDC users go through `prompt=login` + `max_age=300`, checked against the same provider and subject, and a fresh `auth_time`, failing closed.
    - The session id rotates at sign-in and at step-up [R2-M11, R2-M12].
12. **Raw `application/octet-stream` upload** instead of multipart, so nothing is parsed before authentication through the CSRF parameter fallback [R2-M8].
13. **Module Federation** (`@module-federation/vite`), with the SDK as a host singleton, `import:false` in remotes, and shared libraries limited to those that hold state or context [R1-M8].
    - Iframes were rejected: they lose the theme, the slots and the router.
    - Hand-built import maps were rejected: they re-implement Module Federation.
14. **Polling instead of SSE for plugin progress.** SSE is per cluster, and the Admin page has no cluster [R2-M13].
15. **API publishing: the plain jar goes to Maven Central, and the runnable jar gets the `exec` classifier.** A japicmp gate checks the `@PluginApi` surface. The SDK goes to npm through trusted publishing. This mirrors IntelliJ's `@ApiStatus` together with its Plugin Verifier.

## Mechanics (from the approved plan)

## 1. The artifact: one jar
```
acme-notes-1.5.0.jar
  META-INF/artemis-studio/plugin.json    descriptor, read without running plugin code
  META-INF/artemis-studio/icon.svg       optional, ≤64 KB, only ever rendered via <img>
  META-INF/artemis-studio/ui/**          Module Federation remote
  db/changelog/plugin/acme-notes/**      Liquibase formatted SQL
  com/acme/notes/**                      classes; bundled libs relocated under com/acme/notes/shaded
```

**`plugin.json`** is validated against a published JSON Schema. Its fields:
- `schemaVersion`
- `id`: vendor-name, at least two kebab segments, ≤ 50 chars, not `identity-*`
- `name`, `version` (semver), `vendor{name,url,email}`, `description`, `license`
- `changeNotes`, rendered as text
- `basePackage`
- `configuration`, the root `@Configuration` FQCN
- `contract`, `studio{since, until?}`
- `requires[]`
- `ui`
- `activation` (`auto`|`restart`)
- `updateUrl?` (https)
- descriptor facts: `title`, `permissions[]`, `settingKeys[]`, `streamTopics[]`, `mcpTools[]`

**Filled in by the build.** The build writes `contract` and the default `since` from the Studio API it compiled against, **so the minimum version is enforced automatically**. A SNAPSHOT `since` fails the plugin build.

**Allowlist [R1-B2].** Everything outside this list is rejected:
- `META-INF/MANIFEST.MF`, `META-INF/maven/**`, `META-INF/LICENSE*`/`NOTICE*`
- `META-INF/artemis-studio/**`
- `db/changelog/plugin/<id>/**`
- `<basePackage>/**` and `META-INF/versions/*/<basePackage>/**`
- `module-info.class` (ignored)

**Manifest attributes** `Class-Path`, `Launcher-Agent-Class`, `Add-Opens`, `Add-Exports` and `Enable-Native-Access` are rejected [R2-M6].

## 2. Runtime model
**A `PluginRuntime` per active plugin**, owned by `kernel.plugin`'s `PluginHost`, a `SmartLifecycle`:
- **Classloader.** Its own `URLClassLoader`. The parent is `PluginHost.class.getClassLoader()`, which is Boot's `LaunchedClassLoader` [R2-M5], and loading is parent-first. Plugins cannot see each other.
- **API context.** The parent context is curated and holds only the `@PluginApi` beans:
  - `ClusterAccessGuard`, `ActorResolver`, `AuditService`, `BrokerCommands`
  - the rate-limited broker read client
  - `SseHub`
  - `PermissionResolver`, registered as `perm` so `@PreAuthorize` expressions resolve
  - the settings reader and a job facade
  - `JsonMapper`, `Clock`
  - core-event subscription

  Core internals cannot be injected.
- **Its own `GenericWebApplicationContext`,** with **`PluginInfrastructure`** imported by the host [R2-B2]:
  - `@EnableTransactionManagement(proxyTargetClass=true)`, `@EnableMethodSecurity`, `MethodValidationPostProcessor`, `@EnableWebMvc`
  - request and session scopes
  - the plugin's `LocalContainerEntityManagerFactoryBean` (scans `basePackage`, `ddl-auto=validate`), a `JpaTransactionManager` and `@EnableJpaRepositories(basePackage)`
  - a JSON converter built with `jsonMapper.rebuild().build()`, so the serializer caches belong to the plugin
- **An activation assertion:** every bean carrying `@PreAuthorize`, `@Transactional` or `@Validated` must be an AOP proxy. Otherwise the plugin is `failed`, and security is never silently skipped.
- **Its own small `HikariDataSource`** [R2-B3]:
  - `maximumPoolSize=3`
  - `connectionInitSql = SET search_path TO plugin_<id>; SET lock_timeout='10s'; SET statement_timeout='60s'`
  - closed on unload

  This isolates `search_path` from core connections, stops a plugin from starving core, and makes unloading a hard guarantee. Audit writes use the core pool with `REQUIRES_NEW`, per ADR-0078. The data sources are distinct, so there is no pre-bound-connection clash. There is no atomicity between plugin writes and core writes, and the docs say so.
  - **Connection budget:** 10 core + 3 × active plugins must fit under `max_connections`. Admin shows the budget, and activation is refused, with the reason, when it would exceed 80 %.

**Bridges.** Each registers on start and unregisters on stop. Each one sets the thread context classloader (TCCL) for the call and restores it after [R2-M5].
- **HTTP gateway [R2-B1].**
  - One main-context handler owns `/api/v1/p/{id}/**` and `/api/v1/clusters/{clusterId}/p/{id}/**`. It keeps an `AtomicReference<PluginRuntime>` per id, and forwards to a **per-plugin `DispatcherServlet`** built on the child context. MVC caches therefore die with the plugin, and a swap is a single reference set.
  - The gateway answers `404 feature-disabled`, `503 plugin-updating` (`Retry-After`) and `503 plugin-failed`. It counts in-flight requests per runtime and drains them, with a 10 s timeout, before closing.
  - Exceptions go through the child's resolvers first, then the main `handlerExceptionResolver` with `handler=null` (global advices only).
  - springdoc excludes `/api/v1/**/p/**`.
- **MCP [R2-M1].** The server is STATELESS.
  - Plugin tools are built with `org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders.statelessToolSpecifications(beans)` and registered with `McpStatelessSyncServer.addTool`/`removeTool`. Resources and prompts are handled the same way.
  - Each `callHandler` is wrapped with the runtime lookup, the in-flight counter and the TCCL switch.
  - There is no `list_changed`. `McpToolCatalog` reads the registry on every call, so `studio_help` and `studio://tools` are always current, and the static instructions say "installed plugins may add tools; call studio_help".
  - Names use the `<id_snake>_` prefix and stay within the 190-token ceiling.
- **Jobs.** `TaskScheduler.schedule(trigger)`, with the `ScheduledFuture` kept so it can be cancelled on stop and deregistered from `JobStatuses`. `@Scheduled` and `@Async` are denied [R1-M6].
- **Settings, topics, permissions, event replay.** The registries become **volatile copy-on-write snapshots**: `FeatureRegistry`, `SettingsService`'s registry, `StreamController`'s topics and replays, and `McpToolCatalog` [R2-M10].
  - Each plugin entry is removed on stop, including `SettingDef.apply` lambdas, which would otherwise pin the old loader.
  - A plugin whose `apply` throws on its first push becomes `failed`; Studio is unaffected.
- **Core events** are republished into each running plugin. A plugin listener that throws is logged and isolated, and the error never reaches the core publisher.
- **SSE payloads from plugins** must be a `Map` or `JsonNode`, because the main mapper's cache would pin plugin classes.

**Namespaces** (`FeatureRegistry`) mean a future built-in can never collide with a plugin:
- permissions `<id>:*`
- settings `<id>.*`
- topics `<id>[.*]`
- MCP `<id_snake>_*`
- API under the gateway paths
- config `artemis-studio.plugins.<id>.*`

**Unloading.** The host closes the context, unregisters every bridge, closes the pool and the classloader, then tracks the loader with a `WeakReference`. If it has not been collected after GC, it reports "memory still held — restart recommended". The gateway design means this should not happen routinely [R2-B1].

**Boot and liveness [R2-M2, R2-M3].**
- Activation starts after `ApplicationReadyEvent`, one virtual thread per plugin, with a 60 s limit each. On timeout the plugin is `failed` + `needs-restart`. Studio is already serving.
- **Crash-loop guard.** A boot record in Postgres (started / cleanly stopped) triggers automatic **safe mode** after 3 unclean boots in 15 minutes: no plugin starts, and a banner explains why.
- The compose `JAVA_OPTS` gains `-XX:MaxMetaspaceSize=256m`.
- The `ARTEMIS_STUDIO_PLUGINS_SAFE_MODE=true` switch also exists.

**Shutdown.** `PluginHost`'s phase sits below the web server's graceful stop and above STREAM/JOBS. Each plugin gets 5 s to close, and the host survives stop/start, per the `ShutdownStep` contract.

## 3. Static validation (bytecode only; no plugin code runs)
The same `PluginValidator` runs at upload and before every activation.

**Opening the jar [R2-M7].**
- The blob goes to a private temp file, `<tmpdir>/artemis-plugins/<sha>.jar`, mode 0600.
- It is opened with `new JarFile(file, false, OPEN_READ, Runtime.version())`, the same parser the loader uses.
- Rejected: duplicate names, `..`, absolute or backslash names, and nested jars.
- Limits: ≤ 250 MB actually read, ≤ 20 000 entries, ≤ 100:1 ratio per entry, `plugin.json` ≤ 256 KB.

**Bytecode checks.** Bytecode is read with the JDK's `java.lang.classfile`. It checks:
- descriptor, contract, `since..until` (the Studio version comes from `build-info`; SNAPSHOT or IDE builds skip the range with a warning)
- the allowlist and manifest attributes
- confinement, and no overlap with core, the JDK, Jakarta, Spring, Jackson, Hibernate, Liquibase or other plugins
- namespaces, and `requires` present
- denied annotations: `@Scheduled`, `@Async`, `@EnableAutoConfiguration`, `@ComponentScan`/`@Import` outside `basePackage` (`@EnableJpaRepositories` on its own package is allowed)
- `@ConfigurationProperties` prefixes
- denied call sites: `System.exit`, `Runtime.halt`/`exec`, `ProcessBuilder`, `Thread.stop`, `setContextClassLoader`, `HttpSession.setAttribute`

**Liquibase pre-flight.** `validate` catches edited changesets that were already applied. Changesets with `runInTransaction:false` are rejected [R2-M4].

Linkage errors against a changed API surface at activation and quarantine the plugin, naming the class.

## 4. Database (PostgreSQL 18)
- **Store.**
  - `plugin_artifact (content bytea STORAGE EXTERNAL, sha256 PK)` is content-addressed. It has no compression, because a jar is already a zip. Only the current and previous artifact per plugin are kept, and anything unreferenced is removed in the same transaction [R2-minor].
  - `plugin_install (installed_at, activated_at, version, sha256, previous_sha256, vendor, status, failure, id)`, with columns in padding order. It stays tiny, so status updates are HOT updates.
  - `studio_boot (started_at, stopped_at, …)` holds the crash-loop record.
  - Listings never read blobs. A blob is streamed out once per activation, bounded by the 50 MB cap.
- **Schema per plugin.** `plugin_<id_snake>`, created and migrated by the runtime:
  1. Take one dedicated connection from the plugin pool for the whole activation, with `pg_advisory_lock(hashtext('plugin:'||id))` held on it.
  2. Run `ReleaseLocksCommandStep`: this is safe, because the advisory lock proves exclusivity [R2-M4].
  3. Tag `pre-<version>`.
  4. Run Liquibase `update` via `CommandScope`, with `defaultSchemaName` and `liquibaseSchemaName` set to the plugin schema.
  5. Re-read `plugin_install` after locking.

  An activating row counts as stale only if `pg_try_advisory_lock` on it succeeds [R2-§3].
- **Guards (fail the activation).**
  - The set of relations in `public` (excluding `relispartition`, so the nightly metric partitions don't trip it [R2-minor]) must not change between before and after.
  - **No foreign keys from a plugin schema to `public`**, checked in `pg_constraint` [R2-B4]. Plugins store UUIDs and react to the republished core delete events. This protects cluster and user deletion, and future core migrations.
- **Uninstall** keeps the data. **Purge** removes the schema, the `plugin_install` row, grants with prefix `<id>:`, `<id>.*` settings and any unreferenced blobs. It is audited, takes `dryRun`, and reports estimates from `pg_class.reltuples` and `pg_total_relation_size` rather than `count(*)`.
- `SchemaBaselineDiffTest` dumps `public` only.

## 5. Activation classes (shown before confirmation)
| Class | When | What happens | Downtime |
|---|---|---|---|
| **Instant** | install, update or enable with no pending changesets; disable; uninstall; code rollback | The new runtime starts and warms up, then the gateway reference, MCP and bridges swap, then the old runtime drains and closes. If the start fails, the old version keeps serving. Plugins must do no work until their SPI beans are bridged, because `@PostConstruct` runs while both versions are live; the docs say so | none |
| **Brief maintenance** | install or update with pending changesets | Gateway answers 503, jobs are cancelled, in-flight work drains, then migrate with `lock_timeout`, then the new version starts | seconds, this plugin only |
| **Restart** | `activation: restart` declared; a runtime that did not stop cleanly | Status `needs-restart`, with the version to start recorded. When supervised (compose, Kubernetes), Studio exits gracefully and its supervisor starts it again with the plugin active (ADR-0104); otherwise the banner shows the exact command (`docker compose restart studio`) | whole Studio |

**Failures, Studio always up:**
- **Migration fails or hits `lock_timeout`.** The failing changeset is rolled back, the old version resumes, and the plugin is `failed` with the error.
- **Fresh activation fails after migrating.**
  - Reversible: roll back to the tag automatically, which is safe because no new data was written, and the old version resumes.
  - Otherwise: `failed`, "schema at vX". Retry, upload a fix, or uninstall.
- **Crash during activation:** the stale `activating` row is marked `failed` by the lock rule. For crash loops, see §2.
- **Studio upgraded outside `since..until`:** `incompatible`, with Update or Uninstall offered.
- **Temp directory unwritable or full:** `failed` with the reason.
- **Disabling a plugin that others `require`:** the dialog lists the dependants and disables them together.

## 6. Updating
1. **Source.**
   - Drop the new jar on the row or on the tab.
   - Or click **Check for updates**. It is manual, with no background egress, and fetches the `updateUrl` JSON (`version`, `url`, `sha256`, `changeNotes`) over https only. The fetch has a 5 s limit, ≤ 64 KB for the JSON and ≤ 50 MB for the jar, and redirects are not followed.
     - SSRF adds nothing here: only an installer can trigger it, and an installer can already run code.
     - The downloaded jar goes through the same validation.
2. **Review.** The §8 pre-flight: version change, change notes, compatibility, activation class, contribution diff, the count of roles that lose a removed permission, pending changesets with the generated SQL (`update-sql`), and whether the change is reversible. "Reversible" means the schema, not the data.
3. **Confirm.** Step-up and typed confirmation of the id; the action is audited.
4. **Roll back to the previous version** is offered only when the update applied **no** changesets (Instant). Otherwise it is disabled, with the reason: "1.5.0 changed the database. Upload a fixed version or restore from backup." [R2-cut5]

## 7. Who can install, and hardening
**The installer tier [R2-B5].** This is not a role permission, so `user:admin` cannot grant it to themselves.
- **Storage.** The table `plugin_installer (granted_at, granted_by, user_id)` is checked **on every request**, so revocation takes effect immediately.
- **Seeding.** The first installer is the local bootstrap administrator, or the users named in `artemis-studio.plugins.initial-installers` (username, or `registrationId:subject` for OIDC). This makes install on by default for the person who set Studio up.
- **Management.** Only an installer can add or remove installers, with step-up, audited. This is done from "Who can install plugins" in the Plugins tab.
- **Frontend.** The server returns `canInstall` in the plugins view, so the frontend never mirrors this logic.

**Step-up re-authentication** (≤ 5 minutes old) is required to activate, update, roll back, enable or disable, uninstall, purge, and change installers.
- **Local users** re-enter their password. It goes through `LoginAttemptLimiter`, and the session is invalidated after 5 failures [R2-M12].
- **OIDC.** A resolver customizer adds `prompt=login&max_age=300`, only on `/oauth2/authorization/{id}?stepup`. The success handler's step-up branch requires the **same `registrationId` and `sub`**, and `auth_time` within 300 s (**it fails closed** when the IdP omits it). It stamps the session, rotates its id, and returns to the stored URL [R2-M12].
- **Flow:** upload stores an inert `pending` artifact → review → step-up → activate. The step-up never loses the upload.

**Session fixation [R2-M11].** `changeSessionId()` is called in `SessionAuthentication.establish` and at step-up. This is also a standalone `security(auth)` fix, committed first.

**Interactive only.** Callers with an API token (`principal.tokenName() != null`) or through MCP get `403 plugin-install-interactive-only`. The check never looks at the `Authorization` header.

**Upload as a raw body [R2-M8].** `PUT /api/v1/admin/plugins/upload` takes `application/octet-stream`.
- It is copied through a bounded 50 MB stream into the temp file. The global multipart configuration is unchanged, so nothing is parsed before authentication. `request.ts` already sends the CSRF header.
- Limits: at most one activation in flight globally (the advisory lock), and at most 5 uploads per user per hour.

**Kill switch.** `artemis-studio.plugins.upload.enabled=false`. Installing then shows as disabled with the reason, and the API answers `403`.

**Audit.**
- Every lifecycle step is audited with the actor, IP, sha, id, versions and outcome.
- **Each one is also logged as a structured line to stdout** [R2-S9], because plugins share the DB role and could rewrite `audit_event`. This limit is stated in the docs.

**The admin banner.** Every `user:admin` sees plugins that are pending, failed, incompatible, needs-restart, or in safe mode, together with who acted and when.

**UI assets.**
- Served at `/plugin-ui/<id>/<sha8>/**`, authenticated, read by exact `JarFile` entry.
- Exact `Content-Type`, `nosniff`, `Cache-Control: private, immutable`.
- The path is on the SPA no-fallback list. Vite dev proxies it.
- `icon.svg` is served with `Content-Security-Policy: sandbox` and rendered only through `<img>`.
- The docs say: "a plugin's UI acts with the full rights of whoever is viewing it."

**Core bug fixed along the way [R2-M9].** `ApiExceptionHandler` maps every `IllegalStateException` to 502 `notification-delivery-failed`. The mapping is narrowed to the notification exception, as a `fix(core)` commit.

## 8. UI and UX
**Principles.**
- **Say what will happen, to whom, and for how long, before it happens.**
- **Near-monochrome when healthy.** Colour only for Failed, Incompatible or Restart required, and always next to the word.
- **Every problem shows its fix inline.**
- **Background work keeps going.** Closing a dialog never cancels it. A shell-header indicator (the `shell.header` slot) tracks progress, and `aria-live` announces outcomes.
- **Progress is polled.** `GET /api/v1/admin/plugins/{id}` about every 1 s during an activation, and every 30 s otherwise [R2-M13].

**Settings, regrouped.** Settings uses vertical tabs with `?tab=` in the URL. Group headings follow the page's existing escalation: **Yours** → **Studio** → **This cluster** → **Plugins**. Groups come from a new, closed, kernel-owned `group` field on `settings.sections`; plugin sections default to Plugins. The tabs are keyboard-navigable (arrows, Home/End), and focus moves to the panel heading.

**Admin → Plugins.**
- **Header.** The whole panel accepts a dropped `.jar`, with an overlay: "Drop to inspect. Nothing is installed until you confirm." It uses `FileButton` + `onDrop`, with no new dependency. **Install plugin**, **Check for updates**, and "Who can install plugins" (installers only).
- **Table: the existing `VirtualTable`.**
  - monogram or icon; name and vendor; version, with an inline "1.5.0 available"; compatible range
  - **status in words**; contributions ("3 screens · 2 tools · 1 table")
  - tabular numerals
  - rows that need attention sort first, with their fix action in the row
- **Above the table:** an attention summary when something is wrong, and a connection-budget line.
- **Install/update dialog** (a Mantine `Stepper` in a `Modal`).
  1. **Inspect.** A live checklist in words. On failure it lists every violation, what the author must change, and **Copy report**.
  2. **Review: "What this plugin will be able to do."** Grouped as sentences:
     - Screens, API, Assistant tools (read or write), Permissions, Data (new schema, N tables), Depends on
     - For an update, **What changes**: Added/Removed in words, the role impact of removed permissions, and change notes
     - **What happens when you confirm**: the activation class, the downtime, **Show the SQL** (`CodeHighlight`), and "Irreversible: rollback won't be available, take a backup first" with the word and the warning token
  3. **Confirm.** Blast radius, then `ConfirmByTyping` on the id, then step-up (password, or "Re-authenticate with <IdP>"). The primary button names the exact action, for example "Update Notes to 1.5.0 (3 database changes)".
  4. **Progress.** A vertical timeline: Stored → Migrating 2/3 → Starting → Registering → Active. Each step shows elapsed time, and reduced motion is honoured. The outcomes:
     - **Succeeded:** **Open Notes** + "Reload Studio to load its screens".
     - **Failed:** the cause + "1.4.0 is still running" + View error / Retry.
     - **Pending:** the busy button can't be pressed twice.

     The dialog can be closed while it runs.
- **Detail drawer** (`?plugin=<id>` in the URL):
  - **Overview:** sha256 (copy), installed by / when, compatibility, vendor links as text with `noopener`.
  - **Contributions:** links, and which roles grant each permission.
  - **Data:** schema, tables, estimated size.
  - **History:** the audit timeline.
  - **Danger zone:** Disable, Uninstall (data kept), Purge data (after uninstall; dry-run numbers + `ConfirmByTyping`).
- **Empty state.** Explains what plugins are, then the drop area, **Build a plugin →** and **Download the template →**. A user who isn't an installer sees the controls visible but disabled, with the reason reachable by keyboard.

**Plugin UI in Studio** (Module Federation).
- **Host.** `@module-federation/vite` goes in `vite.config.ts` only. The shared singletons are react, react-dom (including `/client` and `/jsx-runtime`), `@mantine/core`, `@mantine/hooks`, `@mantine/notifications`, `@tanstack/react-query`, `@tanstack/react-router`, and `@artemis-studio/plugin-sdk` → `web/src/sdk/`. `requiredVersion` is pinned exactly.
- **`web/src/sdk/`** exposes:
  - the contract types, slots, `NAV_GROUPS`
  - `rootRoute`, `clusterRoute`, `featureView`
  - `request`, `clusterKey`, `ApiError`, `useCan`, `pluginPath()`
  - the shared `ui/*` components

  An eslint boundary keeps it to that surface.
- **Bootstrap.**
  1. A splash screen shows while a raw `fetch` loads the manifest (5 s limit), which then seeds the query cache.
  2. `registerRemotes`, then `Promise.allSettled(loadRemote)` with a 10 s limit each.
  3. `createAppRouter`.

  On 401 the app boots without plugins, and login does a `location.replace`. If the manifest fails, built-ins load with a notice.
- **Reload.** Browsers compare a manifest version on focus and every 60 s. A mismatch shows a non-blocking "Plugins changed: Reload"; the page is never reloaded automatically.
- **Isolation.**
  - Routes live under `/p/<id>/…` and `/clusters/$clusterId/p/<id>/…`, with kernel catch-alls that state the reason and link the row for installers.
  - ErrorBoundaries wrap slots, badges and palette entries.
  - Slot ids are namespaced, only declared topics are accepted, and only existing nav groups are used.
- `FeatureId` stays the built-in union; `StudioFeature.id: FeatureId | PluginId`.

**Quality gates.**
- Contrast is measured in both schemes.
- There is a keyboard-only pass for every step of the dialog.
- Tests query by role and accessible name.
- `npm run ui-review` screenshots cover each state and each outcome.

## 9. Authoring
- **Java API on Central.** `io.github.sudoitir:artemis-studio` (the plain jar, `provided` scope), with `@PluginApi` types.
  - The repackaged jar becomes `exec`. **`Dockerfile:28` and `ci.yml:190` switch to `artemis-studio-exec.jar`**, with a container smoke test [R1-B6].
  - The pom gains `developers`, `scm`, sources, javadoc (`-Xdoclint:none`), gpg and `central-publishing-maven-plugin`.
  - A `japicmp` check on the type-level `@PluginApi` surface fails a break made without a `Contract.VERSION` bump. It is skipped when there is no baseline yet.
- **npm `@artemis-studio/plugin-sdk`.**
  - `.d.ts` files, without the router `Register` augmentation.
  - peerDependencies pinned exactly.
  - The preset `studioPlugin({ id })` handles the shared entries (`import:false`), the relative base and the output folder. It fails the build on an unshared `@mantine/*` import or its CSS.
- **Verifier.** `PluginVerifier.main(jar)` (the exact §3 validator, plus warnings for irreversible changesets), and ArchUnit rules:
  - `@PluginApi` only
  - mutations depend on `AuditService` or `BrokerCommands`
  - no raw broker HTTP
- **`examples/plugin-template/`** (GitHub template). It contains:
  - a pom with shade and relocate, `plugin.json` filtering and `frontend-maven-plugin`
  - `web/` using the preset
  - a `notes` sample: entity, reversible changelog, controller with `@PreAuthorize`, MCP tool, route, slot and settings tab
  - the verifier run

  **Studio CI builds it and installs, updates and rolls it back through the API in e2e.**

## Failure matrix (Studio always starts)
| Case | Outcome |
|---|---|
| Upload invalid (schema, allowlist, manifest attrs, zip limits, confinement, namespace, contract, range, denied call, `runInTransaction:false`, edited changeset) | `422`, every violation listed, nothing stored |
| Same id, different vendor / downgrade | refused: "purge first" / "use Roll back" |
| Missing or disabled `requires` | `failed`, naming the dependency |
| Linkage error, or `@PreAuthorize`/`@Transactional` bean not proxied | `failed`, naming the class; the old version keeps serving |
| Start hangs (> 60 s) | `failed` + `needs-restart`; Studio already serving |
| Migration error or `lock_timeout` | rolled back; the old version resumes; `failed` |
| Irreversible migration, then start fails | `failed` "schema at vX"; retry / fix / uninstall |
| Stale Liquibase lock after a crash | released under the advisory lock |
| Crash during activation / crash loop | `failed` / automatic safe mode after 3 unclean boots in 15 min |
| Studio outside `since..until` | `incompatible` |
| Blob sha mismatch; temp dir unwritable | `failed`, with the reason |
| New relation in `public`, or an FK into `public` | activation fails, the old version resumes |
| Connection budget over 80 % | activation refused, with the reason |
| In-flight requests, tool calls or jobs at a swap | drained (10 s), then `503 plugin-updating` |
| Disabled / uninstalled | `404 feature-disabled`; data kept; purge available |
| Plugin listener, `apply` or topic handler throws | isolated to that plugin, logged |
| UI remote fails, or a component throws | catch-all / ErrorBoundary; built-ins unaffected |
| Old UI after a backend update | manifest version mismatch → "Reload" |
| Stale session, token/MCP caller, non-installer, rate limit | `403 reauthentication-required` (not 401: the SPA treats 401 as signed out) / `403` / `403` / `429` |
| Overlapping deploy (two instances briefly) | advisory locks serialise; running two instances is documented as unsupported |

## Risks / Trade-offs

- [Reflection can bypass the bytecode denials; plugins share the DB role, so the audit table can be edited] → the installer tier, step-up, a stdout copy of every lifecycle audit line, admin banners. The docs state that plugins are trusted.
- [Classloader leaks are possible] → the gateway design removes the known core pins. A `WeakReference` check surfaces "restart recommended", and `MaxMetaspaceSize` bounds the damage.
- [A major bump of React, Mantine or TanStack breaks plugin UIs] → the contract bumps, versions are pinned exactly, and CI rebuilds the template.
- [The dynamic registries and the gateway are new kernel machinery] → a throwaway spike with explicit exit criteria comes first. Built-ins keep their startup path.
- [The Module Federation host affects every built-in screen] → bundle-size gate (+5 %), shots diff and the full vitest suite.
- [Irreversible plugin migrations] → flagged in the review with "take a backup first".

## Migration Plan

- No backward compatibility, per the user's decision.
- PostgreSQL 18 is a separate `build(deploy)!:` commit: existing 17 volumes are not read.
- The runnable jar is renamed `artemis-studio-exec.jar` (**BREAKING** for anyone running the jar directly). The Dockerfile and CI change with it.
- The new tables arrive in a new `kernel/plugin` changelog.
- Rolling back the release means redeploying the previous image. Plugin tables are then ignored, and plugin schemas are left in place.

## Open Questions

- The exact Spring AI annotation-provider method names, the Liquibase `CommandScope` step names and the Module Federation runtime API are confirmed through ctx7 in the spike. They do not change the approach.

## Spike results (task 3, 2026-09-23)

**Backend: all criteria met** (a throwaway `PluginSpikeTest` against the full application context and Postgres 18):
- A curated parent exposes `perm` and `AuditService`; a non-exported `ClusterAccessGuard` is not resolvable.
- `PluginInfrastructure` works: `@PreAuthorize("@perm…")` answers 403, and controllers are CGLIB-proxied.
- The gateway forwards to a per-plugin `DispatcherServlet`: 40 concurrent requests across a v1→v2 `AtomicReference` swap, with zero failures.
- Per-plugin Hikari + EMF + `JpaTransactionManager` + Liquibase `CommandScope` (update + tag): the plugin's table exists only in `plugin_<id>`, and core `search_path` is unchanged.
- `SyncMcpAnnotationProviders.statelessToolSpecifications` and `McpStatelessSyncServer.addTool`/`removeTool` work at runtime; the tool is listed, callable and removable.

Facts the implementation relies on:
- `SyncMcpAnnotationProviders` lives in `org.springframework.ai.mcp.annotation.spring`.
- Use `GenericWebApplicationContext` + `AnnotatedBeanDefinitionReader`. `AnnotationConfigWebApplicationContext` rejects `registerSingleton` before `refresh`.
- Liquibase with a manually built `Database` needs **both** `provideDependency(Database.class, db)` and `addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, db)`.
- A plugin's root configuration **must** `@ComponentScan` its own base package. The validator requires it, because without it a plugin silently registers no beans.
- Plugins compile with `-parameters` (the template sets it). Jars must contain directory entries, or package scanning finds nothing.
- Singletons registered manually (the EMF, the pool) get no inferred destroy method. The host closes them explicitly on unload, in this order: drain → context → EMF → pool → loader.
- A `@Configuration` meant for a child context must be top-level, never nested in a `@SpringBootTest` class, or Boot's test bootstrap adopts it.

**Open item carried into task 6.2.** In the spike, the old classloader was **not** collected after unload, and no live thread referenced it. The likely cause is JVM-wide caches keyed by class (`CachedIntrospectionResults`, `java.beans.Introspector`, `ReflectionUtils`, `AnnotationUtils`, `ResolvableType`, Jackson type caches). The runtime clears the known caches on unload. The task then finds and removes any remaining GC root from a heap dump, and a test asserts the loader is collected. The "restart recommended" signal stays as the backstop.

**Frontend: all criteria met except the size budget.**
- Runtime `registerRemotes` + `loadRemote` from `@module-federation/runtime` renders a remote route under `clusterRoute`, with its CSS.
- `base: './'` makes the remote servable from `/plugin-ui/<id>/<sha8>/`.
- The Vite dev server is fine, and vitest is unaffected.

Resolutions:
- **A shared module is only registered when host code imports it.** `main.tsx` imports `@artemis-studio/plugin-sdk`, and the loader checks the share scope at startup.
- **SDK resolution** is an npm `file:src/sdk` dependency (a managed symlink), not a hand-made link.
- **Bundle size was +7.4 % gzip against a +5 % target.**
  - The shared set is trimmed: `react-dom/client` is dropped (plugins never create roots), and `@mantine/notifications` is replaced by an SDK `notify()` function.
  - The size is measured again. If the Module Federation runtime overhead alone still exceeds 5 %, the accepted ceiling is +8 %, recorded with the measured first-load time. The runtime is the fixed cost of loading plugins at all.
