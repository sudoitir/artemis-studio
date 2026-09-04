## MODIFIED Requirements

### Requirement: Manual address overrides are never overwritten by discovery

A node whose address has been set manually SHALL be marked as overridden, and
subsequent discovery runs SHALL NOT change its addresses. Only an explicit
update SHALL change an overridden node's address. This applies independently to
the node's management (Jolokia) URL and its Core URL: either may be set
manually, a manual value SHALL take precedence over the value learned from
topology, and a node-override request SHALL supply at least one of the two.

#### Scenario: Override survives rediscovery

- **WHEN** a node's management URL is set manually and rediscovery then runs
- **THEN** that node's address is unchanged and it remains marked as overridden

#### Scenario: Manual Core URL survives rediscovery

- **WHEN** a node's Core URL is set manually and rediscovery then runs
- **THEN** the node's Core URL is unchanged and it remains marked as overridden

#### Scenario: Node override requires at least one URL

- **WHEN** a node-override request supplies neither a management URL nor a Core URL
- **THEN** the request is rejected with a validation message

#### Scenario: Missing side is not a deletion

- **WHEN** a topology response omits the `backup` entry for a pair (for example
  right after failover)
- **THEN** Studio treats the backup side as not currently announced and does not
  delete the node row
