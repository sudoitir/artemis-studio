## Why

A consuming plugin sometimes cannot process anything for a while through no fault of the message:
the service it calls is down and its circuit breaker is open. Today it can only reject the message,
which counts as a delivery attempt. On a broker with no redelivery delay, the same message comes
back at once and is rejected again, so it runs out of `max-delivery-attempts` and is dead-lettered
although nothing was ever wrong with it. With several broker nodes, each node's consumer does the
same while the plugin backs off. A plugin author has no way to say "not now, and not your fault".

## What Changes

- `Disposition` gains `RELEASE`: the plugin is not done with the message and the message is not the
  reason. A consumed message goes back to the queue with its delivery count unchanged, so the broker's
  delivery-attempt limit never dead-letters it for a release. A tapped copy is discarded, as with
  `REJECT`.
- Plugin consumers acknowledge each message on its own (Artemis `INDIVIDUAL_ACKNOWLEDGE`), not the
  session's messages together (`CLIENT_ACKNOWLEDGE`). Under client acknowledge the JMS client settles
  a message at Core level before the handler runs, so every hand-back counts. Under individual
  acknowledge the message stays delivering until the handler answers. A release then rolls the Core
  session back without considering the message delivered, and the broker takes the delivery back.
  `REJECT` recovers the session as today and still counts, so a rejected message is still bounded
  by `max-delivery-attempts`. Consumers already take one message at a time (no prefetch window), so
  acknowledging one message never settles another. Taps are unchanged.
- The plugin guide explains when to release and when to reject, and warns that a handler which keeps
  receiving while it releases gets the same message again at once: a plugin releases while it stops
  its own registration.

## Capabilities

### New Capabilities
- None.

### Modified Capabilities
- `plugin-messaging`: a consumer can release a message without spending a delivery attempt.

## Impact

- `feature/plugins/messaging/Disposition.java` (`@PluginApi`, one constant added), `PluginDrains`,
  `platform/broker/CorePool` (the consumer sessions' acknowledge mode, `PooledSession.release`).
- Additive for plugins: japicmp reports the added constant as compatible, so `Contract.VERSION` stays.
  A plugin that returns `RELEASE` needs the Studio release that has it, which its `studio.since`
  already says.
- Docs: `site/src/guide/plugins.md`.
