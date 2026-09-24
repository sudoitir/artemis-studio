## Context

See [ADR-0111](../../../docs/adr/0111-plugin-scoped-beans-and-plugin-messaging.md) for the decisions. This file records how they map onto the code.

## Decisions

- **Scoped beans.** `kernel.plugin.PluginScopedBeans#beansFor(pluginId)`; `PluginRuntimeFactory` registers the result as singletons in the plugin context before `refresh()`.
- **Messaging** lives in `feature.plugins` (package `messaging`, tables under `db/changelog/feature/plugins/`), which already owns plugin administration: `PluginMessagingService` (behind the per-plugin `PluginMessaging` facade), `PluginMessagingReconciler` (job + startup sweep, `ClusterLock.Scope.PLUGIN_MESSAGING`), `PluginTap` (divert + ring queue under `artemis-studio.plugin.<instance>.<registration>`), `PluginDrains` (Core consumers on `CorePool`'s capture pool), `PluginHandlers` (a `PluginBridge` that finds each plugin's `PluginMessageHandler` through `PluginHandle.beansOfType` and calls it through `runInPlugin`). Drains use their own `CorePool` pool (`borrowForPlugins`), so their exception listener never replaces capture's.
- **Disabled features.** With the queues or SQL feature off, taps are refused with that reason; consumers and sends still work.
- **Tap objects** reuse `DivertOperations`, `QueueLifecycleOperations` and capture's broker role, expiry and byte bound. The divert is non-exclusive on the queue's address, with the queue's filter. `DivertOperations` treats the new prefix as reserved, and capture refuses patterns that resolve to it.
- **Consumer** drains use `CLIENT_ACKNOWLEDGE`. `ACCEPT` acknowledges; `REJECT`, an exception, or no active handler recovers the session so the broker redelivers.
- **Permissions** are re-read with `GrantLoader` on every pass. When they are lost, the drain stops, the tap is removed and the state becomes `SUSPENDED` with the reason.
- **Secrets** live in `kernel.security`: `PluginSecretStore` and the table `plugin_secret`, sealed by `SecretVault` with AAD `plugin|<id>|<name>`, and purged on `PluginPurged`.

## Risks / Trade-offs

- A tap copies the address's traffic through the queue's filter, as capture does. A queue that only ever receives FQQN sends to another queue on its address is not separable this way.
- A handler that blocks holds its own session only; timeouts and limits are the plugin's.
