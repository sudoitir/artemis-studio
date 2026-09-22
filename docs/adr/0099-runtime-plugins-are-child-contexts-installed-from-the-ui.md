# ADR-0099: Runtime plugins are Spring child contexts behind a gateway, installed from the UI into Postgres

- **Status**: accepted
- **Date**: 2026-09-22
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/plugins`
- **Supersedes in part**: [ADR-0069](0069-kernel-plugin-modular-monolith.md) — its "composition is at build time" and "no runtime plugin loading" clauses. Built-in features remain composed at build time.
- **Builds on**: [ADR-0070](0070-extension-contract-and-feature-manifest.md), [ADR-0078](0078-audit-row-commits-before-the-broker-call.md)

## Context

A third party cannot extend an installed Studio. The only path is to fork the repository, overlay files and build a private image. Operators want plugins like WordPress's or IntelliJ's: installed from the UI, updated with a preview, rolled back, and never able to take Studio down.

The container has no writable volume: Postgres is Studio's only state. Studio is single-instance. The MCP server is stateless. Several registries (features, settings, topics, the MCP catalogue, jobs) were built once, at startup.

Two adversarial reviews rejected the first designs, which used one flat plugin classloader in the main context and `registerMapping` into the main MVC mapping:
- plugin beans could override or post-process core beans;
- a plugin's `SpringLiquibase` bean would switch off the core migrations;
- the main MVC caches, keyed by `Class`, would keep every old classloader alive;
- security annotations would be silently ignored in a bare child context.

## Decision

- **Each plugin is one jar with a `plugin.json` descriptor. It is uploaded in the UI, stored in Postgres and never on disk except as a private temp copy.** It runs in its **own classloader and its own Spring `GenericWebApplicationContext`**.
- **The parent of every plugin context is a curated API context** that holds only the beans marked `@PluginApi`. A plugin cannot inject core internals, and its post-processors and `@Primary` beans affect only its own context.
- **The host imports a fixed `PluginInfrastructure` into every plugin context:** transactions, method security, method validation, Web MVC, request and session scopes, and the plugin's own JPA. **A plugin whose annotated beans are not proxied is not activated.**
- **Plugin HTTP goes through one gateway.** A main-context handler owns `/api/v1/p/{id}/**` and `/api/v1/clusters/{clusterId}/p/{id}/**`.
  - It forwards to a per-plugin `DispatcherServlet` through an `AtomicReference`, so a swap is a single reference set.
  - It counts in-flight requests and drains them before a runtime closes.
  - It answers `404 feature-disabled`, `503 plugin-updating` and `503 plugin-failed` itself.
- **Other contributions arrive through bridges into copy-on-write registries:** MCP tools (`McpStatelessSyncServer.addTool`/`removeTool`), jobs, settings, stream topics, permissions and core events. Each bridge unregisters on stop and switches the thread context classloader for every call.
- **Every change is classified before confirmation:**
  - **Instant:** the new version starts, then the swap, then the old version drains.
  - **Brief maintenance:** the plugin alone answers 503 while its migrations run.
  - **Restart:** declared by the plugin, or a runtime did not stop cleanly. Studio states the command and never restarts itself.
- **Safe mode instead of failing fast.** Plugins activate after `ApplicationReadyEvent` with a 60 s limit each. A boot record starts Studio with no plugins after 3 unclean stops in 15 minutes. **A plugin can never stop Studio from starting.**
- **Validation reads bytecode with `java.lang.classfile` and opens the jar with `JarFile`,** the same parser the loader uses. **No plugin code runs during inspection.**

## Consequences

- An installation can be extended without a rebuild, and most updates cause no downtime.
- The kernel gains machinery that has to be maintained: the gateway, dynamic registries and a runtime lifecycle. Built-in features keep their startup path unchanged.
- Plugins are trusted in-process code. The checks prevent accidents and misuse of the contract; they do not stop a hostile author, who can use reflection. The docs say so.
- Classloader leaks remain possible. They are detected with a `WeakReference` and reported as "restart recommended", and `MaxMetaspaceSize` bounds the damage.
- There is no atomicity between a plugin's own writes and core writes.

## Alternatives considered

- **A mounted plugins directory loaded at startup.** Needs a writable volume, doesn't work for a UI-first install, and a bad plugin fails startup. Rejected by the user.
- **One flat classloader in the main context.** No isolation, and no unloading. Rejected by both reviews.
- **PF4J or OSGi.** They add their own lifecycle and classloading model on top of Spring's, and the Spring integration still needs the same bridges.
- **Separate plugin processes (sidecars).** Strong isolation, but no shared UI or transactions, a network hop for every call, and a second deployable. That contradicts ADR-0007.
