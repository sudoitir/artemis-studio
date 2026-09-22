# ADR-0102: The plugin API is published to Maven Central and npm, guarded by a binary-compatibility gate

- **Status**: accepted
- **Date**: 2026-09-22
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/plugins`
- **Amends**: [ADR-0042](0042-calver-releases-on-docker-hub.md) — a release also publishes the plugin API

## Context

Plugin authors outside the repository need the Java types and the UI kit they compile against, from standard registries, with a way to tell whether a Studio release broke them.

## Decision

- **Maven Central:**
  - Each release publishes `io.github.sudoitir:artemis-studio` as the **plain jar**, with sources, javadoc and signatures, for use as a `provided` dependency.
  - The runnable jar takes the `exec` classifier (`artemis-studio-exec.jar`), which the Dockerfile and the release job use.
  - The types plugins may use carry **`@PluginApi`**.
  - **japicmp** compares the `@PluginApi` surface with the previous release. A binary-incompatible change without a `Contract.VERSION` bump fails the build.
- **npm:** each release publishes **`@artemis-studio/plugin-sdk`**, containing the `.d.ts` files of `web/src/sdk` and the `studioPlugin()` Vite preset. It uses **trusted publishing (GitHub OIDC)**, so no npm token is stored.
- **`PluginVerifier`** ships in the plain jar and runs the upload inspection offline.
- **CI builds `examples/plugin-template` against the release candidate,** then installs, updates and rolls it back.

## Consequences

- Authors depend on standard coordinates, and an unannounced API break cannot ship.
- Every CalVer release is permanent on Central.
- The plain jar exposes internals at compile time. `@PluginApi`, the verifier and the runtime's curated context keep plugins honest; this mirrors IntelliJ's `@ApiStatus` and its Plugin Verifier.
- **BREAKING:** anyone running `artemis-studio.jar` directly must use `artemis-studio-exec.jar`.

## Alternatives considered

- **GitHub Packages.** Consumers need a token even to read.
- **Release assets plus a template repository.** Non-standard dependency resolution.
- **A separate Maven module for the API.** Restructures the single-module build (ADR-0069) for no runtime gain.
