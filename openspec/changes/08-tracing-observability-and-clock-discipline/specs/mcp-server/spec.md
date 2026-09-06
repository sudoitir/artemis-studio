## MODIFIED Requirements

### Requirement: Request-reply tracing is reachable through one tool

The request-reply tool SHALL expose flows, statistics, declared expectations and
**diagnostics** behind a single discriminator argument, and SHALL NOT add a tool
per mode. The diagnostics mode SHALL return the same account of why tracing is or
is not producing flows that the interface presents.

#### Scenario: An assistant asks why there are no flows

- **WHEN** a client calls the request-reply tool with the diagnostics mode
- **THEN** it receives the sampler's account per traced address and the ranked
  reasons with their remedies

#### Scenario: The listing cost is unchanged

- **WHEN** the tool listing is generated
- **THEN** adding the diagnostics mode has added no tool and no argument, and the
  listing budget still passes

### Requirement: Cluster health states its own freshness and clock confidence

The cluster-health result SHALL state when it was assembled, and SHALL include the
system's current verdict on the clocks involved — the verdict, the largest measured
offset and its uncertainty, and which nodes are affected.

#### Scenario: A model can tell how stale an answer is

- **WHEN** a client receives a cluster-health result
- **THEN** it carries the time the answer was assembled

#### Scenario: A model is told the numbers may be measured against a wrong clock

- **WHEN** the system's clock verdict is anything other than agreement
- **THEN** the cluster-health result carries that verdict and the affected nodes
