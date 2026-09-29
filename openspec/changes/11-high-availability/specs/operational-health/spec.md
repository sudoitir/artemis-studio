## ADDED Requirements

### Requirement: Replicas share caches and leadership coherently
Cached state SHALL be coherent across replicas, and every poller or scheduled duty SHALL run on one leader or be partitioned so that no work is done twice or skipped.

#### Scenario: Leader lost
- **WHEN** the leader replica stops
- **THEN** another takes over within a stated time and no duty is left unserved

#### Scenario: Cache change
- **WHEN** a setting changes on one replica
- **THEN** the other replicas serve the new value within a stated time

### Requirement: Probes report what an orchestrator needs
Studio SHALL expose startup, liveness and readiness probes: liveness fails only when the process cannot recover itself, readiness fails while the instance cannot serve or is draining, and neither depends on a broker being reachable.

#### Scenario: Broker down
- **WHEN** a broker is unreachable
- **THEN** readiness and liveness stay healthy and the cluster shows degraded

#### Scenario: Draining
- **WHEN** shutdown begins
- **THEN** readiness fails first

### Requirement: In-flight operations finish or fail cleanly on shutdown
Shutdown SHALL wait a bounded time for in-flight operations and SHALL record any that could not finish.

#### Scenario: Bulk run at shutdown
- **WHEN** a bulk run is in flight
- **THEN** it completes or is recorded as interrupted with its progress

### Requirement: A reference HA deployment exists and is tested
The repository SHALL contain a compose template with two replicas, a load balancer and Postgres, documented as the reference, and failover tests SHALL run against it.

#### Scenario: Kill a replica
- **WHEN** one replica is killed under load
- **THEN** clients continue through the other and the tests pass
