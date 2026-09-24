## Why

A plugin that consumes a queue gets exactly one consumer per node, and every Core consumer prefetches 64 KiB. A plugin whose handler calls a slow service therefore processes one message at a time, while up to 64 KiB of further messages sit in Studio's buffer, out of reach of other consumers. It cannot ask for more parallelism, and it cannot rely on the broker to hold back what it has not started.

Plugin handlers also run on the Core client's global thread pool, which message capture and operator sessions share. Enough plugin handlers that block can take every thread of that pool and stall capture on the same cluster.

## What Changes

- **BREAKING** `RegistrationSpec` gains `int concurrency`: 1 to 32 for a consumer, exactly 1 for a tap. Any other value is refused with the reason. `MessageRegistration` reports it. Registering the same key with another concurrency restarts the drains with the new count.
- Studio opens `concurrency` sessions, each with one consumer, per serving node.
- Consumers get no prefetch window (`consumerWindowSize=0`). At most `concurrency` messages per node are delivered and unsettled, and the rest stay on the queue.
- Order within a message group (`JMSXGroupID`) is the broker's message grouping. Studio documents it and adds nothing of its own.
- Plugin drains use Core connection factories with their own thread pools (`useGlobalPools=false`), bounded by `artemis-studio.plugins.messaging.max-threads` (64 by default, at least 2). Capture and operator sessions keep the global pool.
- `Contract.VERSION` and the UI `CONTRACT` become 2. Plugins built for contract 1 are refused until they are rebuilt.
- The plugin guide documents concurrency, flow control, group order and the plugin thread pool. The API check is skipped automatically when the contract version is raised.

## Capabilities

### New Capabilities
- None.

### Modified Capabilities
- `plugin-messaging`: consumers state their concurrency and get flow control; message groups stay in order; plugin handlers run on a thread pool of their own.

## Impact

- `@PluginApi` break: `RegistrationSpec` and `MessageRegistration` have a new component, so their canonical constructors change. The contract version is raised. Builds on ADR-0111 and ADR-0102; decided in ADR-0112.
- Changeset `feature-plugins-0002` adds `plugin_message_registration.concurrency` (default 1, so existing registrations keep one consumer per node).
- The CI job that publishes the plugin API skips the binary-compatibility check for a release that raises `Contract.VERSION`.
