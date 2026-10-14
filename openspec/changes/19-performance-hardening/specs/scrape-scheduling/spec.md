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
- **THEN** one slow-tier tick reads every page of that node, one after the other,
  so the whole queue set is refreshed within one slow-tier interval

#### Scenario: A slow-tier walk that fails part way removes nothing

- **WHEN** a slow-tier walk fails or is interrupted before its last page
- **THEN** no cache row is removed for that node, and the next tick walks it again
  from the first page

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

### Requirement: One batched request per node per tick

For every scrape tick, the system SHALL issue exactly one Jolokia bulk POST per
node — an array of read and exec entries — never one request per attribute,
operation, or queue. The slow tier is the one exception: it SHALL issue one POST per
page of a node's queue list, each through the node's management-call ceiling.

#### Scenario: Fast tick is a single POST

- **WHEN** the fast tier needs HA attributes plus `listNetworkTopology()` for a node
- **THEN** Studio sends one POST whose body is a JSON array of those entries and
  reads each entry's own status from the aligned response array

#### Scenario: A failed entry does not abort the tick

- **WHEN** one entry in a batched response has a non-200 status
- **THEN** Studio consumes the successful entries and records the failure without
  discarding the tick or skipping other nodes


### Requirement: Metric samples are written and retained for a bounded window

The system SHALL append queue counter samples to the metric history on the
medium and slow tiers, and SHALL delete history older than a configurable
retention window. The default retention SHALL be seven days. History SHALL be
retained in daily partitions with UTC bounds and no default partition, so that
trimming expired history is a partition drop rather than a row-by-row delete.
A sample for a queue, node and time that is already stored SHALL be ignored,
not stored twice, so that a repeated scrape does not duplicate it.

#### Scenario: Old samples are trimmed

- **WHEN** a partition's contents are entirely older than the configured
  retention window
- **THEN** that partition is dropped and its samples are no longer queryable

#### Scenario: A sample is scraped twice

- **WHEN** the same sample is written a second time, for instance after a retry
- **THEN** the second write is ignored and the history holds one row

#### Scenario: A sample has no partition

- **WHEN** a sample's day has no partition
- **THEN** the partition is created and the write retried once, as for any partitioned history

#### Scenario: Samples written before partitioning still age out

- **WHEN** samples sit in a partition that is only partly older than the
  retention window
- **THEN** they remain until the whole partition is older than the window, and
  are then dropped with it
