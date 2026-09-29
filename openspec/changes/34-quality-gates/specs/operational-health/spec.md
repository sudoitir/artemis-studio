## ADDED Requirements

### Requirement: Health output reflects failover and outage states
Operational health SHALL distinguish a node that is unreachable, a node that has failed over and a database that is unavailable, and SHALL never report an unreachable node as healthy.

#### Scenario: Failover state
- **WHEN** a backup takes over
- **THEN** health shows the change of role

#### Scenario: Database down
- **WHEN** the database is unavailable
- **THEN** health reports it and the readiness probe reflects it
