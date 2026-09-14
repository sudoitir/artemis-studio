## MODIFIED Requirements

### Requirement: Scraping is tiered by cost and volatility

The system SHALL poll each manageable node on three independent schedules: a
fast tier for HA and topology state, a medium tier for the busiest queues, and a
slow tier that sweeps every queue. Each tier's interval SHALL be configurable.
The fast tier's interval SHALL be short enough that corroborated split-brain is
detectable within roughly two of its cycles. Completion of the fast tier for a
cluster SHALL trigger evaluation of that cluster's state-condition alert rules;
completion of the medium and slow tiers' per-cluster fan-out SHALL trigger
evaluation of that cluster's metric-threshold alert rules. Alert evaluation
SHALL NOT delay or block the scrape tick it is triggered from.

In addition, the system SHALL poll a cluster's client activity — producers and
consumers — on a demand-driven schedule
that runs only while that cluster's flow is observed. Its interval SHALL be
configurable with a lower bound, it SHALL obey the same one-request-per-node and
per-node ceiling rules as the tiers, and it SHALL NOT run at all for a cluster
nobody is observing.

#### Scenario: Fast tier reads HA and topology

- **WHEN** the fast tier runs for a manageable node
- **THEN** Studio reads that node's HA attributes and network topology in one
  request and updates the node's live/replication state

#### Scenario: Slow tier eventually covers every queue

- **WHEN** a node has more queues than fit in one page
- **THEN** successive slow-tier ticks advance through the pages until the whole
  queue set has been refreshed, then the sweep restarts

#### Scenario: Medium tier refreshes active queues sooner

- **WHEN** a queue had consumers or a non-zero depth at its last reading
- **THEN** that queue is re-read on the medium tier without waiting for the full
  slow-tier sweep

#### Scenario: Fast tier completion evaluates state-condition rules

- **WHEN** the fast tier finishes a cluster's tick
- **THEN** that cluster's state-condition alert rules (split-brain, node down,
  replication behind, cluster degraded) are evaluated against the state just
  persisted

#### Scenario: Medium and slow tier completion evaluates metric-threshold rules

- **WHEN** the medium or slow tier finishes persisting a cluster's queue snapshots
  or metric samples for a tick
- **THEN** that cluster's metric-threshold alert rules are evaluated against the
  state just persisted

#### Scenario: Client activity is polled only while observed

- **WHEN** a cluster's flow stops being observed and its observation lease expires
- **THEN** the client-activity schedule issues no further requests to that
  cluster's nodes until it is observed again
