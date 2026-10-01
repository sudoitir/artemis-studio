# ADR-0153: Plugins declare a license, Studio stores the file, the plugin judges it

- **Status**: accepted
- **Date**: 2026-10-01
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/plugin-licensing`

## Context

A plugin may be sold, but Studio has no notion of a license. An administrator cannot tell that a plugin
needs one, there is nowhere to put the file, and a plugin that wants to check one has to invent its own
storage and its own screen. Studio is open and generic: it must not learn any license scheme, vendor or
key, and it must not be able to degrade because a plugin's license is wrong. The pieces it already has
fit: per-plugin scoped beans ([ADR-0111](0111-plugin-scoped-beans-and-plugin-messaging.md)), the plugin
administration API and its installer tier ([ADR-0103](0103-plugin-installer-tier-and-step-up-reauthentication.md)),
the replica bus ([ADR-0152](0152-replicas-coordinate-through-postgres.md)) and the bridge that republishes
`@PluginApi` events into every running plugin.

## Decision

1. **The plugin declares.** `plugin.json` gains `"requiresLicense": true` (optional, default false). A
   plugin that does not declare it shows no license state and refuses an upload.
2. **Studio stores opaque bytes.** The file is at most 64 KiB, kept in `plugin_license` (one row per
   plugin, in Postgres, so every replica serves the same file and verdict) and never interpreted. It is
   deleted when the plugin is purged and kept when it is uninstalled. It is not encrypted at rest: a
   license is signed by its issuer, not secret.
3. **The plugin judges.** A scoped `@PluginApi` bean `PluginLicense` gives the plugin its own file
   (`file()`) and takes its verdict (`report(sha256, Verdict)`: status valid, expired, over limit or
   invalid, an expiry, a licensee and a short detail). No method takes a plugin id, so one plugin can
   neither read nor judge another's license. A report for a hash that is no longer the stored file's is
   ignored in the same SQL statement that would write it, so a late report cannot mark a replaced file
   valid.
4. **Plugins are told.** A write publishes a bus signal in its own transaction. Every replica, the
   writer's included, republishes it as `@PluginApi PluginLicenseChanged(pluginId)` into its running
   plugins, through the existing event bridge. A listener that throws is isolated by the bridge. A
   replica whose bus was down tells every plugin with a file when it reconnects.
5. **Administrators see a derived state:** `MISSING`, `UNCHECKED` (a file with no verdict yet), the
   reported status, and `EXPIRING` when a valid license ends within 30 days. A valid verdict whose expiry
   has passed shows as expired. The plugin list carries a badge, and the plugin drawer has a License tab
   to upload, replace and remove the file.
6. **Upload and removal are guarded.** `PUT` and `DELETE /admin/plugins/{id}/license` need a plugin
   installer in a browser session with a sign-in inside the step-up window, and each is audited with the
   plugin, the file's hash and its size, never the content, in the audit entry, a log line or a problem
   detail. A body over 64 KiB is refused with 413, an empty one with 400 and a content type other than
   raw bytes with 415, and none of them changes the stored file.
7. **Health, not readiness.** The `pluginLicenses` contributor is degraded while a running plugin that
   needs a license has none, has one its plugin has not accepted (or not looked at for five minutes), or
   has one that ends within 30 days. It is computed from the shared table and is not part of readiness,
   so an unlicensed plugin never takes Studio out of rotation.
8. **A fact to size a license by.** `StudioInfo.brokerInstances()` is the number of broker nodes
   registered in the installation, a count with no names. A plugin may size a license by it, or anything
   else.

Nothing in Studio's start-up, its other plugins or the host depends on a verdict.

## Consequences

- A third-party plugin author can license a plugin with their own scheme and no change to Studio.
- Studio cannot tell a forged license from a real one: it shows what the plugin says. That is the point,
  and the plugin's own check is only as strong as its jar, which publisher signing
  ([ADR-0141](0141-plugin-jars-are-signed-and-verified-against-pinned-keys.md)) protects.
- A plugin that never reports leaves its file `UNCHECKED`, and the health degrades after five minutes, which
  is how an administrator learns the plugin is not running its check.
- Every addition is compatible with plugins built for the earlier contract. `Contract.VERSION` still rises to 8 in
  the release that carries this change, because japicmp flags the stream hub's constructor, which the replica bus
  ([ADR-0152](0152-replicas-coordinate-through-postgres.md)) changed and which no earlier version raised.
- A license is not secret, so anyone who can read the database can read the file. The signature protects it
  from being altered, not from being read.

## Alternatives considered

- **Studio verifies signatures against vendor keys that administrators register.** It would put one license
  scheme into Studio, and an administrator could register a key of their own and mint licenses.
- **Keep the file in `PluginSecrets`.** Secrets are never shown and are audited per name, not as license
  operations, the administrator could not see whether the license is valid, and the plugin would get no
  change event.
- **Studio calls a license-check method on the plugin.** It would make Studio's screens wait on plugin code.
  A plugin that reports asynchronously cannot hold anything up.
