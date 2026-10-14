## How to run this change
This change states **requirements only**. Run it in a fresh Claude session, in number order:

1. `git fetch`, then create a git worktree for this change on a new branch off `origin/main` (`.claude/rules/00-workflow.md`). Work only there, and remove it after the merge.
2. Read this proposal, its specs, the `broker-sdk` capability and its ADRs, and the role mapping table in the archived `19c-broker-sdk` design.
3. Brainstorm and investigate (`/opsx:explore`, `superpowers:brainstorming`); check the client libraries with ctx7. Ask the user only what is really theirs to decide.
4. `/opsx:update`: add `design.md`, sharpen the specs, replace the stub `tasks.md`.
5. `/opsx:apply` with the harness under **Execution**. New decisions get an ADR. A gap in the SDK is fixed in the SDK, for every driver, never worked around in this driver.
6. Verify: the driver's tests and the TCK against a real broker; Studio with a cluster of this system next to an Artemis cluster, with screenshots of the shared screens (light and dark, empty and error states).
7. PR, merge on green CI, `/opsx:archive`.

## Why
ActiveMQ Classic still runs in many estates next to Artemis. Its model is closest to Artemis (queues, topics, durable subscribers, virtual topics, networks of brokers), so a driver brings those estates into Studio with the least strain on the SDK. It is additive, so it runs after the stable release.

## What Changes
- A driver, `<sdk>-activemq-classic`, implementing the SDK for Apache ActiveMQ Classic through Jolokia and OpenWire
- The driver passes the TCK for every capability it declares, and declares unavailable what the system cannot do
- Studio registers clusters of this system and shows them in the estate view; system-only screens come from a `system/activemq-classic` module
- Queues and topics are destinations, queues and durable subscribers are subscriptions, and virtual topics, composite destinations and network connectors are routes; destination policies and authorization entries are policies and access rules
- Browse up to the broker's browse depth, which is declared as a capability; move, copy and delete by id and by selector
- Events through advisory topics

## Capabilities
### New Capabilities
- `broker-sdk-activemq-classic`: The Apache ActiveMQ Classic through Jolokia and OpenWire driver.
### Modified Capabilities
- `broker-sdk`: Only where this system exposes a gap in the SDK; any break is taken before the SDK's `1.0`.

## Out of scope
- Managing the system's own deployment (operators, Helm, cloud services)

## Depends on
- 19f-rabbitmq-driver (the SDK settled by two drivers before the stable release)

## Execution
**Subagent-driven**: the driver's facets are separable, and each has TCK cases.

## Impact
A new driver module, a Studio system module, the TCK, documentation, and the CI matrix.
