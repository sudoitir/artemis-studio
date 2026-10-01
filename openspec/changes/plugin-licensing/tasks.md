## 1. Contract and storage

- [x] 1.1 ADR-0153.
- [x] 1.2 `requiresLicense` in `plugin.schema.json`, `PluginDescriptor` and the plan review, with parser tests.
- [x] 1.3 Changeset `kernel-plugin-0010-plugin-license`, entity and repository.
- [x] 1.4 `PluginLicense`, `PluginLicenseChanged` and `PluginLicenseStore`, with tests for scoping, stale reports, caps, purge, uninstall and the change signal.
- [x] 1.5 `StudioInfo.brokerInstances()`, with tests.

## 2. Administration

- [x] 2.1 `PUT` and `DELETE /admin/plugins/{id}/license` with installer and step-up checks, audit, and 400, 413 and 415 refusals; `PluginView.license`; regenerated OpenAPI document and web types.
- [x] 2.2 The `pluginLicenses` health contributor.
- [x] 2.3 Runtime integration tests with real plugins: upload reaches the plugin, its verdict shows, removal notifies it, one plugin cannot see another's file, a failing plugin affects no other; the change signal reaches a second replica.

## 3. UI and documentation

- [x] 3.1 `LicenseBadge`, the drawer's License tab with upload, replace and remove, and tests for every state.
- [x] 3.2 The plugin guide's licensing sections and the template's note.
- [x] 3.3 japicmp: nothing in this change breaks, but the stream hub's constructor (replica bus) does, so `Contract.VERSION` is raised to 8.
- [ ] 3.4 `just verify`, screenshots in light and dark with a test plugin, pull request, CI and the quality gate green.
