## Context

See [ADR-0112](../../../docs/adr/0112-plugin-consumers-set-their-concurrency-on-a-thread-pool-of-their-own.md) for the decisions. This file records how they map onto the code.

## Decisions

- **Validation** is in `PluginMessagingService.validate`, and the column carries a matching check constraint (`ck_plugin_message_registration_concurrency`).
- **Slots.** `PluginDrains` keeps one drain per `(registration, node)`, holding one slot per unit of concurrency. Each slot is a `CLIENT_ACKNOWLEDGE` session and one consumer. Every slot starts, or none does. Stop, close and abandon act on every slot. The reconciler compares the running slot count with the stored concurrency and restarts a drain whose count differs.
- **Pools.** `CorePool.borrowForPlugins(…, unbuffered, maxThreads)` keeps two pools per node: taps (the bounded 64 KiB window, so a tap's single consumer keeps pace) and consumers (`consumerWindowSize=0`). Both factories set `useGlobalPools=false` and `threadPoolMaxSize`. Stopping a pool also closes its factory, which releases its threads.
- **Order.** Nothing in Studio: the broker pins a group to one consumer, and with no window that consumer takes the group's next message only after it settles the last one.
- **Release.** `publish-api` compares `Contract.VERSION` at the release tag with the previous release tag, and passes `-Djapicmp.skip=true` only when it changed.

## Risks / Trade-offs

- `consumerWindowSize=0` costs a round trip per message. That is small next to what a handler that needs concurrency does.
- Two plugin pools per node, so `max-threads` bounds each of them. Blocked handlers beyond it wait for a thread, not for capture's.
- A group stays in order only within a node. Order across a broker cluster needs the broker's grouping handler.
