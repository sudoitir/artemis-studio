## 1. Consumer concurrency and flow control

- [x] 1.1 ADR-0112 and this change
- [x] 1.2 `RegistrationSpec.concurrency` and `MessageRegistration.concurrency`; validation (consumer 1..32, tap 1) with the reason; changeset `feature-plugins-0002` with a rollback
- [x] 1.3 `PluginDrains`: one slot (session and consumer) per unit of concurrency per node; stop, close and abandon act on every slot; a changed concurrency restarts the drain
- [x] 1.4 `CorePool`: plugin pools with `useGlobalPools=false`, `threadPoolMaxSize` from `artemis-studio.plugins.messaging.max-threads` (default 64, at least 2); consumers with `consumerWindowSize=0`; stopping a pool closes its factory

## 2. Contract

- [x] 2.1 `Contract.VERSION` and `CONTRACT` = 2; plugin template, tests and fixtures follow
- [x] 2.2 `publish-api` skips japicmp when the release raises `Contract.VERSION`
- [x] 2.3 Plugin guide: concurrency, flow control, group order, the plugin thread pool

## 3. Tests

- [x] 3.1 Invalid concurrency is refused (consumer 0 and 33, tap 2)
- [x] 3.2 Real broker: N handlers run at once; a stalled plugin holds at most N unsettled messages and the rest stay on the queue; group order at concurrency above 1; a changed concurrency restarts the drain; blocked plugin handlers do not delay capture on the same cluster
