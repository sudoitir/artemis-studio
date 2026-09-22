## Why

Today every Studio capability is compiled in (ADR-0069). Extending Studio means forking it, overlaying files with a `register.patch` and building a private image, so no third party can add a screen, an API or an assistant tool to an existing installation. Operators want plugins in the style of WordPress and IntelliJ: install one from the UI, see exactly what it will do, update it with a preview and roll it back, and never have a bad plugin take Studio down.

## What Changes

- **Runtime plugins.** A plugin is one jar holding a descriptor (`plugin.json`), backend classes, its own Liquibase changelog and a Module Federation UI bundle.
  - Studio runs each plugin in its own Spring child context and classloader, behind a curated API context. Plugin HTTP goes through one gateway, and the plugin contributes MCP tools, jobs, settings, stream topics and permissions through dynamic registries.
  - Built-in features are unchanged and stay compiled in.
- **Installed from the UI only.**
  - A new Admin → Plugins tab: upload → automatic inspection → a review of what the plugin will be able to do → typed confirmation + step-up re-authentication → live progress.
  - Artifacts are stored in Postgres, and there is no plugins directory.
  - Install is on by default, with a kill switch.
- **Activation without restart where safe.** Every change is classified before confirmation:
  - **Instant**: no downtime; the old version keeps serving if the new one fails.
  - **Brief maintenance**: database changes; only this plugin pauses.
  - **Restart**: stated with the exact command.
- **Updates** come from a dropped jar or a manual "Check for updates" against the plugin's `updateUrl`. Each shows a preview: permission, API and tool diff, the generated SQL, and whether the change is reversible. Code-only updates can be rolled back.
- **Safe mode.** A plugin that is invalid, incompatible after a Studio upgrade, hangs, or crashes Studio repeatedly is quarantined with its reason and a fix. Studio always starts.
- **Isolated data.** Each plugin gets its own Postgres schema, connection pool and EntityManager factory. Plugins cannot create objects in `public` or foreign keys into it.
- **Installer tier.** A new privilege, held in its own table and checked on every request, which role editing cannot grant. It is seeded with the bootstrap administrator.
  - Plugin lifecycle actions need re-authentication within the last 5 minutes: password, or OIDC `prompt=login` + `max_age` with the same subject.
  - API tokens and MCP callers cannot install.
- **Session id rotation.** The session id is rotated at sign-in and at step-up. This closes a session-fixation gap in local login.
- **Settings is reorganised into grouped vertical tabs** (Yours / Studio / This cluster / Plugins), with the open tab in the URL.
- **Authoring kit.**
  - `io.github.sudoitir:artemis-studio` published to Maven Central, with `@PluginApi` types.
  - `@artemis-studio/plugin-sdk` published to npm: types and a Vite preset.
  - `PluginVerifier`.
  - A plugin template, which CI builds, installs, updates and rolls back on every release.
- **BREAKING (packaging).** The runnable jar is renamed `artemis-studio-exec.jar`, and the plain jar becomes the published API artifact.
- **Docs.** "Build a plugin" (compile into Studio, `register.patch`) is replaced by the "Plugins" guide.

## Capabilities

### New Capabilities
- `plugin-runtime`: plugin artifact and descriptor, validation, storage, runtime isolation, activation classes, update and rollback, safe mode, installer tier and step-up for plugin actions, the plugin UI contract, and the authoring kit.

### Modified Capabilities
- `feature-modules`: a feature can be a runtime plugin. The manifest reports each feature's origin, version and status, and namespaces reserve plugin contributions so a future built-in cannot collide with them.
- `authorization`: plugin management is governed by the installer tier, not a role permission, and ordinary role editing cannot grant it.
- `identity-and-sessions`: the session id is rotated on sign-in. Sensitive actions can require step-up re-authentication, and repeated step-up failures end the session.
- `oidc-sso`: step-up re-authentication through the identity provider, bound to the same subject and a fresh `auth_time`.
- `mcp-server`: the tool surface and its generated description include the tools of active plugins, and change as plugins are activated and removed.
- `operator-ui`: Settings is presented as grouped tabs. A plugin's address explains its state (disabled, updating, failed, incompatible, not installed), and a failing plugin component never breaks the surrounding screen.

## Impact

- **Backend.**
  - `kernel.plugin`: host, runtime, gateway, validator, store, migrations and safe mode.
  - Registries made dynamic: `FeatureRegistry`, `SettingsService`, `StreamController`, jobs, `McpToolCatalog`.
  - `kernel.security`: installer tier, step-up, session rotation.
  - `feature.identityoidc`: the step-up branch.
- **Database.** New core tables `plugin_artifact`, `plugin_install`, `plugin_installer` and `studio_boot`, plus one `plugin_<id>` schema per plugin.
- **Frontend.**
  - `@module-federation/vite` host (new dependency), the async bootstrap, `web/src/sdk/`, the `/p/<id>` routes and catch-alls.
  - Settings tabs, and a new `plugins` feature (Admin tab).
- **Build and release.**
  - The `exec` classifier (`Dockerfile`, `ci.yml`), `build-info`, and Central publishing (gpg, sources, javadoc, `central-publishing-maven-plugin`).
  - japicmp, the npm package with trusted publishing, and the template in CI.
  - Compose `JAVA_OPTS` gains `MaxMetaspaceSize`.
- **Depends on ADRs** 0069 (superseded in part), 0070, 0072 (amended), 0042 (amended), 0078 and 0047. New ADRs: plugin runtime; Module Federation; per-plugin schema and pool; API publishing; installer tier and step-up.
