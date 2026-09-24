## 1. Host

- [x] 1.1 `PluginScopedBeans` SPI and registration in `PluginRuntimeFactory`
- [x] 1.2 ADR-0111

## 2. Secrets

- [x] 2.1 `plugin_secret` changeset, `PluginSecretStore`, `PluginSecrets` facade bound per plugin
- [x] 2.2 Audit put/delete without values; delete on `PluginPurged`
- [x] 2.3 Tests: isolation, purge, AAD tamper, audit carries no value

## 3. Messaging

- [x] 3.1 API types in `feature.plugins.messaging`, tables
- [x] 3.2 Registration service: validation, reserved objects, permissions
- [x] 3.3 Tap install/remove, reserved prefix in `DivertOperations` and capture
- [x] 3.4 Drains for taps and consumers, dispositions, dropped count
- [x] 3.5 Reconciler, startup sweep, suspension and resume, lifecycle nudges, purge cleanup
- [x] 3.6 Send
- [x] 3.7 Integration tests against a real broker

## 4. Docs and release

- [x] 4.1 Plugin guide section
- [x] 4.2 `just verify`
