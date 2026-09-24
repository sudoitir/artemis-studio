## Why

A plugin that lets a user configure where it sends messages can only learn that a target is refused (a reserved address, an unknown cluster, a user without `message:send`) when the first message goes out. It cannot tell the user while they configure it, and it cannot reuse Studio's reserved-address rules without copying them, which would drift.

## What Changes

- `PluginMessaging.checkSend(clusterId, address, actingUserId)` returns the reason a send would be refused, or nothing, without sending.
- `send` applies the same check, so the answer and the send never disagree. A send to a cluster that is not registered is now refused with that reason, rather than failing later for want of a serving node.

## Capabilities

### New Capabilities
- None.

### Modified Capabilities
- `plugin-messaging`: a plugin can check a send before making it.

## Impact

- One additive `@PluginApi` method; `Contract.VERSION` is unchanged. Plugin guide updated. Builds on ADR-0111.
