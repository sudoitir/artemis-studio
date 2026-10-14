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
Kafka is the most widely run streaming platform, and its model is the furthest from Artemis: a partitioned log, consumer groups with committed offsets, no per-message delete, retention instead of acknowledgement. A Kafka driver brings Rahdar's estate view to Kafka estates. It is also the strongest test of the SDK, and it runs before the stable release, while a gap it finds can still be fixed with a breaking change.

## What Changes
- A driver, `openbroker-kafka`, implementing the SDK for Apache Kafka through the Admin and Consumer and Producer clients
- The driver passes the TCK for every capability it declares, and declares unavailable what the system cannot do
- Rahdar registers clusters of this system and shows them in the estate view; system-only screens come from a `system/kafka` module
- Topics are destinations, partitions are partitions, consumer groups are consumer groups, and a group on a topic is a subscription whose backlog is the lag, reported as an estimate
- Purge of a subscription is a seek to the end, and truncate of a topic is `deleteRecords`; both are previewed with their effect
- Browse by partition, offset and time; a transacted transport through Kafka transactions; events through the support library's polling watch

## Capabilities
### New Capabilities
- `broker-sdk-kafka`: The Apache Kafka through the Admin and Consumer and Producer clients driver.
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
