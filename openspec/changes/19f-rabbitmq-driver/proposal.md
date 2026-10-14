## How to run this change
This change states **requirements only**. Run it in a fresh Claude session, in number order:

1. `git fetch`, then create a git worktree for this change on a new branch off `origin/main` (`.claude/rules/00-workflow.md`). Work only there, and remove it after the merge.
2. Read this proposal, its specs, the `broker-sdk` capability and its ADRs, and the role mapping table in the archived `19c-broker-sdk` design.
3. Brainstorm and investigate (`/opsx:explore`, `superpowers:brainstorming`); check the client libraries with ctx7. Ask the user only what is really theirs to decide.
4. `/opsx:update`: add `design.md`, sharpen the specs, replace the stub `tasks.md`.
5. `/opsx:apply` with the harness under **Execution**. New decisions get an ADR. A gap in the SDK is fixed in the SDK, for every driver, never worked around in this driver.
6. Verify: the driver's tests and the TCK against a real broker; Rahdar with a cluster of this system next to an Artemis cluster, with screenshots of the shared screens (light and dark, empty and error states).
7. PR, merge on green CI, `/opsx:archive`.

## Why
RabbitMQ is the most widely run AMQP broker, and its routing model differs from both Artemis and Kafka: virtual hosts, exchanges that route to queues by binding, classic, quorum and stream queues, shovels and federation. A RabbitMQ driver brings Rahdar's estate view to RabbitMQ estates. It is the second test of the SDK before the stable release.

## What Changes
- A driver, `openbroker-rabbitmq`, implementing the SDK for RabbitMQ through its management HTTP API, AMQP 0-9-1 and the stream protocol
- The driver passes the TCK for every capability it declares, and declares unavailable what the system cannot do
- Rahdar registers clusters of this system and shows them in the estate view; system-only screens come from a `system/rabbitmq` module
- Virtual hosts are namespaces, exchanges are destinations, queues are subscriptions, and bindings, exchange-to-exchange bindings, shovels and federation links are routes; policies and permissions are policies and access rules
- Browse only where it is non-destructive (streams); a destructive get-and-requeue is never offered as browse
- Settlement through acknowledgements, without a transacted batch; events through the event exchange where it is enabled, and the polling watch where not

## Capabilities
### New Capabilities
- `broker-sdk-rabbitmq`: The RabbitMQ through its management HTTP API, AMQP 0-9-1 and the stream protocol driver.
### Modified Capabilities
- `broker-sdk`: Only where this system exposes a gap in the SDK; any break is taken before the SDK's `1.0`.

## Out of scope
- Managing the system's own deployment (operators, Helm, cloud services)

## Depends on
- 19d-broker-neutral-surface (the estate view and the neutral public surface)

## Execution
**Subagent-driven**: the driver's facets are separable, and each has TCK cases.

## Impact
A new driver module, a Rahdar system module, the TCK, documentation, and the CI matrix.
