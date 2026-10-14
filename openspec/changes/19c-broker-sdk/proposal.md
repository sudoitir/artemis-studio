## How to run this change
This change states the requirements and a settled design (`design.md`). Run it in a fresh Claude session, in number order:

1. `git fetch`, then create a git worktree for this change on a new branch off `origin/main` (`.claude/rules/00-workflow.md`). Work only there, and remove it after the merge.
2. Read this proposal, `design.md`, its specs, the capabilities it names in `openspec/specs/`, and the ADRs they cite.
3. Check libraries with ctx7. Brainstorm only what the design leaves open; ask the user only what is really theirs to decide.
4. `/opsx:update`: sharpen the specs (turn ADDED into MODIFIED where a requirement changes an existing one in `broker-connectivity`, `broker-capabilities`, `database-schema` and the feature capabilities) and refine `tasks.md`.
5. `/opsx:apply` with the harness under **Execution**, task group by task group; each group leaves a working product. New decisions get an ADR.
6. Verify: the narrow checks per group; the full suite on CI; `just demo`, including HA.
7. PR, merge on green CI and an empty Sonar list, `/opsx:archive`.

## Why
Studio can manage only Apache ActiveMQ Artemis, and Artemis reaches into every layer: 18 files import the Artemis client, 13 modules send raw MBean operations through `JolokiaBrokerClient`, and the data model uses Artemis nouns. Studio is to manage Kafka, RabbitMQ and ActiveMQ Classic too, in one estate view. That needs a broker-neutral abstraction with Artemis behind it. The abstraction is worth shipping on its own: a framework-free broker SDK with a driver SPI and a conformance kit, which other tools for other brokers can adopt.

## What Changes
- **BREAKING** The repository becomes a Maven reactor: the SDK artifacts (`openbroker-api`, `-support`, `-tck`, `-bom`), the Artemis driver (`openbroker-artemis`) and the app under `studio/`. The SDK has its own SemVer, `0.x` until Studio's stable release.
- A broker-neutral SDK: a typed resource graph (open driver-defined kinds over closed neutral roles), facets per concern (`Nodes`, `Resources`, `Operations`, `Messages`, `Transport`, `Events`, `Access`), operation preview with preconditions, partitions and shards, conditions, and errors on gRPC canonical codes.
- The Artemis driver holds all Jolokia, Core protocol, MBean, notification and relay code. Studio's per-call controls (rate limiter, call guard, call health, observations, clock offset) plug in through the SDK's `CallInterceptor`.
- Every Studio feature runs on the SDK facets, with identical behaviour, wire traffic and load on Artemis. The proof is recorded golden files of the broker traffic plus the TCK.
- Broker-only product features (broker.xml configuration, the Artemis setup rules and remedy snippets) move to a `system/artemis` module.
- **BREAKING** The data model uses broker-neutral names (destination, subscription, kind, plane, system). Module baselines are edited in place, with no migration.
- **BREAKING** The stored and exported metric names become the SDK's standard metric keys.
- **BREAKING** `BrokerCommands` and `BrokerClientFactory` leave the plugin contract, because they expose Jolokia; `Contract.VERSION` is bumped.
- The approval gate takes each operation's effect estimate and precondition from the driver's preview.

## Capabilities
### New Capabilities
- `broker-sdk`: The broker-neutral SDK: model, facets, driver SPI, errors, support library and conformance kit.
- `broker-sdk-artemis`: The Artemis driver, and its no-behaviour-change guarantee.
### Modified Capabilities
- `database-schema`: Broker-neutral table and column names.
- `broker-connectivity`, `broker-capabilities`, `broker-events`, `core-transport`, `message-capture`, `message-transfer`, `metrics`, `scrape-scheduling`: The same requirements, now met through the SDK; `/opsx:update` writes the MODIFIED deltas.

## Out of scope
- The neutral REST, MCP, UI wording and plugin message types (19d).
- Drivers for other systems (19e, 19f, 37).
- The product rename (19b).

## Depends on
- 19b-product-identity: it chooses the SDK's name, and its rename lands first, so the SDK is never published under temporary coordinates.

## Execution
**Subagent-driven, in order.** The task groups are sequential, because each rests on the previous one. Within a group, file-disjoint work may run in parallel, with at most two heavy commands at once.

## Impact
The whole backend, the build, CI, the Dockerfile, Sonar, the database schema, metric names, the plugin contract (`Contract.VERSION`), and the ADRs.
