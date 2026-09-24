## Why

A runtime plugin (ADR-0099) cannot receive or send broker messages, and it cannot keep a secret. Plugins may not run threads of their own, so a plugin cannot bring its own broker client, and Studio's `@PluginApi` beans are shared singletons that do not know which plugin is calling. Operators who want an automation that reacts to messages therefore have to run a custom consumer outside Studio.

## What Changes

- **Plugin messaging.** A plugin can register a **tap** on a queue (a bounded copy of what is routed to it, without touching existing consumers or producers) or a **consumer** (receive, then accept or reject each message), and can **send** a message with a body, headers and properties.
  - Studio owns every connection, thread and broker object. Registrations are stored and converged by a reconciler, so crashes, failover, broker restarts and several Studio instances need no special handling.
  - Every registration and send acts for a named user and needs that user's cluster permissions: `message:read` for a tap, `message:read` and `queue:purge` for a consumer, `message:send` to send. A registration whose user loses a permission is suspended with the reason, and resumes when the permission returns.
  - Disabling, uninstalling or purging a plugin removes what its registrations created on the broker.
- **Plugin secrets.** Each plugin has a vault of named secrets, encrypted with Studio's secret key, readable only by that plugin, and deleted on purge. No interface returns a value.
- **Scoped plugin beans.** The host can put beans bound to one plugin into that plugin's context. `PluginMessaging` and `PluginSecrets` are the first two.
- The plugin guide documents the new API.

## Capabilities

### New Capabilities
- `plugin-messaging`: plugins tap, consume and send messages through Studio.
- `plugin-secrets`: a vault per plugin for secrets.

### Modified Capabilities
- None. The additions to the plugin API are additive; `Contract.VERSION` is unchanged.

## Impact

- `feature.plugins` gains the messaging API and its tables (`plugin_message_registration`, `plugin_message_registration_node`); `kernel.security` gains `plugin_secret`; `kernel.plugin` gains a host SPI (`PluginScopedBeans`) and `PluginHandle.beansOfType`.
- Broker objects under the new reserved prefix `artemis-studio.plugin.`, restricted to `artemis-studio.capture.broker-role` like capture's.
- ADR-0111. Depends on ADR-0009, ADR-0062, ADR-0077, ADR-0079, ADR-0099, ADR-0102.
