## ADDED Requirements

### Requirement: The SDK is a framework-free library with a driver SPI
The SDK SHALL ship as `<sdk>-api` (consumer API and driver SPI in separate packages, with a `module-info.java`), `<sdk>-support`, `<sdk>-tck` and `<sdk>-bom`, targeting Java 25. `<sdk>-api` SHALL depend on nothing except nullness annotations. Drivers SHALL be found through `ServiceLoader`. The SDK SHALL carry no Studio type and no product brand.

#### Scenario: An outside project adopts the SDK
- **WHEN** a project with no Spring and no Studio dependency adds `<sdk>-api` and a driver
- **THEN** it can connect to a cluster and use every facet the driver offers

#### Scenario: A driver is discovered
- **WHEN** a driver jar is on the class or module path
- **THEN** `ServiceLoader` finds its `BrokerDriver`, and the driver names its system, supported versions and connection schema

### Requirement: Resources have an open kind over a closed role
Every resource SHALL be identified by a `ResourceId` of a driver-defined kind and a segmented path, with one canonical, percent-encoded text form that parses back to the same id. Every kind SHALL map to one neutral role: namespace, destination, partition, subscription, consumer group, route, policy, access rule, principal, connection, session, producer or consumer. Every kind SHALL declare its verbs and an attribute schema that marks each attribute as config, status or metric.

#### Scenario: Shared code reasons about roles
- **WHEN** a consumer lists the subscriptions of two clusters of different systems
- **THEN** it gets resources of both systems' native kinds, each with the role `SUBSCRIPTION` and the standard attribute keys

#### Scenario: A canonical name round-trips
- **WHEN** a resource id whose path segments contain `/` or `%` is formatted and parsed
- **THEN** the parsed id equals the original

### Requirement: Health is expressed as conditions
Resources and nodes SHALL carry conditions (type, true/false/unknown, reason, message, last transition), so health is comparable across systems.

#### Scenario: A blocked destination
- **WHEN** a destination stops accepting messages because it is full
- **THEN** its resource carries a condition that says so, with the native reason

### Requirement: Work is scoped to shards
Message and operation calls SHALL take a scope: the whole resource, or a set of shards, where a shard is a node instance or a partition. Placements SHALL name the node, the optional partition and the role (instance, leader or follower).

#### Scenario: A partitioned subscription is browsed
- **WHEN** a consumer browses one partition of a subscription from its latest position
- **THEN** only that partition's messages are returned, with a cursor to continue

### Requirement: Operations are previewed and guarded
Operations SHALL be a closed set of commands: create, update (a merge patch of writable config), delete, pause, resume, purge, seek, truncate, reset counters and close. A preview SHALL return the diff, the attributes that require a replace, an effect estimate and a state key. An operation carrying an expected state key SHALL be refused with `FAILED_PRECONDITION` if the state changed.

#### Scenario: A purge is approved after the queue grew
- **WHEN** a purge previewed at 1,000 messages runs with that state key after the backlog changed
- **THEN** it is refused and nothing is purged

#### Scenario: Purge and truncate are different effects
- **WHEN** a consumer previews a purge of a subscription and a truncate of its destination
- **THEN** the purge affects only that subscription, and the truncate is reported as affecting every subscription of the destination

### Requirement: Positions, totals and filters are honest
Positions SHALL be earliest, latest, per-shard offsets, a time, or after a cursor. Totals SHALL say whether they are exact, an estimate, or unavailable. A filter SHALL name its dialect; the portable `<sdk>.match` dialect SHALL work on every system, evaluated client side by `<sdk>-support` where the broker cannot.

#### Scenario: A lag-based total
- **WHEN** a driver can only derive a backlog from offset distances
- **THEN** the total is reported as an estimate, never as exact

#### Scenario: A portable filter on a broker without selectors
- **WHEN** a consumer counts a subscription's messages with an `<sdk>.match` filter on a system without server-side filtering
- **THEN** the count is computed client side and is correct

### Requirement: Transport separates settlement from transactions
Receivers and senders SHALL settle messages with accept, release, reject or modify. Transacted batches SHALL be a capability a driver declares, not an assumption.

#### Scenario: A relay on a system without transactions
- **WHEN** a consumer asks a driver without the transacted capability for a transacted sender
- **THEN** the call fails with `UNIMPLEMENTED`, and the capability check had already said so

### Requirement: Events follow a watch contract
Events SHALL use a CloudEvents-shaped envelope (id, source, type, subject, time, data), with native types namespaced. When a stream may have lost events, the watcher SHALL receive a gap event telling it to re-list. A system without push events SHALL offer events through the support library's polling watch.

#### Scenario: A reconnect loses notifications
- **WHEN** a node's event stream reconnects
- **THEN** the watcher receives a gap for that node before any new event

### Requirement: Capabilities are static and dynamic
Static capabilities SHALL be the verbs of each kind. Dynamic capabilities SHALL be checked through `Access`, returning available, unavailable or unknown with a reason and, where the broker can be changed to allow it, a remedy.

#### Scenario: A management account cannot write
- **WHEN** the configured account lacks write permission
- **THEN** the write capability is unavailable with the reason and a remedy snippet, and the verbs of the kinds are unchanged

### Requirement: Errors use canonical status codes
Every failure SHALL be a `BrokerException` with a gRPC canonical status, a retryable flag, the node where known, and the native error code. Interruption SHALL surface as `CANCELLED`. A missed deadline SHALL surface as `DEADLINE_EXCEEDED`. Results across nodes SHALL be partial, naming each node that failed.

#### Scenario: One node of three is down
- **WHEN** a read spans three nodes and one is unreachable
- **THEN** the result holds the two nodes' data and an `UNAVAILABLE` failure for the third

### Requirement: Callers control every call
Drivers SHALL ask the caller's `CallInterceptor` for a permit before every broker request, and report its outcome on that permit. Per-call context (deadline, principal, trace, approval id) SHALL travel in a `ScopedValue` the caller binds.

#### Scenario: A rate limit applies inside a batch
- **WHEN** a single facet call makes several broker requests
- **THEN** the interceptor admits each request and sees each outcome

### Requirement: Drivers prove conformance
The TCK SHALL test every capability a driver declares against a real broker the driver supplies, and SHALL skip what the driver declares unavailable.

#### Scenario: A driver claims a verb it does not honour
- **WHEN** a driver lists `purge` for a kind and a purge leaves messages behind
- **THEN** the TCK fails
