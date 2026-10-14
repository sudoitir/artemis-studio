## ADDED Requirements

### Requirement: Artemis is a driver of the SDK
All Artemis-specific transport code (Jolokia, the Core protocol client, MBean names, notifications, relay and broker.xml remedy snippets) SHALL live in `openbroker-artemis`. No other module SHALL import the Artemis client or Jolokia types; build-time architecture tests SHALL enforce this.

#### Scenario: A feature needs an Artemis-only function
- **WHEN** Studio's broker.xml configuration needs Artemis management
- **THEN** it reaches it through `unwrap` in the `system/artemis` module, and nowhere else

### Requirement: Moving behind the SDK changes no behaviour
For every operation Studio performs on Artemis, the requests sent after this change SHALL match the recorded golden files of the previous version: the same bodies, headers and number of requests per node. That covers scrape, browse, purge, bulk move, the capability probe and staging.

#### Scenario: Scrape load is unchanged
- **WHEN** a scrape cycle runs against a node
- **THEN** it sends the same single batched request as before

#### Scenario: The relay keeps its guarantees
- **WHEN** a transfer is killed between its two commits
- **THEN** no message is lost or duplicated, as before

### Requirement: The Artemis driver passes the TCK
The Artemis driver SHALL pass every TCK case for the capabilities it declares, against a real Artemis broker.

#### Scenario: CI runs the TCK
- **WHEN** the driver's integration tests run
- **THEN** every declared capability's TCK cases pass
