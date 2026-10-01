## Why

A plugin may be sold, and Studio has no notion of a license: nothing tells an administrator that a plugin needs one, nothing stores one, and a plugin has no place to ask for it. Studio must stay generic and open, so it offers only a neutral contract. A plugin may say it needs a license, administrators upload the file, Studio stores it and shows whether the plugin accepts it, and the plugin reads the file and judges it. Checking, issuing and enforcing a license belong to the plugin.

## What Changes

- A plugin's descriptor may state `"requiresLicense": true`.
- Administrators who can install plugins upload, replace and remove a license file per plugin, after a fresh sign-in, with every attempt audited without the file's content.
- Studio stores the file as opaque bytes (at most 64 KiB), shows the plugin's verdict (valid, expiring, expired, over limit, invalid) and expiry in the plugin list and drawer, and degrades its operational health while a running plugin's license needs attention.
- New `@PluginApi` surface: a scoped `PluginLicense` bean (`file()`, `report(sha256, verdict)`), a `PluginLicenseChanged` event delivered to the plugins on every replica, and `StudioInfo.brokerInstances()`.
- Every addition is compatible (japicmp). `Contract.VERSION` is raised to 8 in the same release for an earlier, unrelated break japicmp flags: the stream hub's constructor changed with the replica bus.

## Capabilities

### New Capabilities
- `plugin-licensing`: the generic license contract.

### Modified Capabilities
- `plugin-runtime`: the descriptor states whether the plugin requires a license.

## Out of scope

- Checking signatures, issuing or enforcing licenses: plugins own all of it.
- Any Studio feature that requires a license.
- License servers, activation calls or telemetry.

## Impact

Plugin API (`@PluginApi`), plugin runtime, plugin administration API and UI, database (`plugin_license`), operational health, SDK documentation. See ADR-0153.
