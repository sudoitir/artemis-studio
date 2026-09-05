# scrape-scheduling Specification

## Purpose
Defines how Artemis Studio periodically reads state from every manageable broker
node without becoming a load problem: a tiered schedule, one batched request per
node per tick, a per-node cap on management calls, and how the polled results
are cached and aged out.

## Requirements

### Requirement: Scraping is tiered by cost and volatility

The system SHALL poll each manageable node on three independent schedules: a
fast tier for HA and topology state, a medium tier for the busiest queues, and a
slow tier that sweeps every queue. Each tier's interval SHALL be configurable.
The fast tier's interval SHALL be short enough that corroborated split-brain is
detectable within roughly two of its cycles.

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

### Requirement: One batched request per node per tick

For every scrape tick, the system SHALL issue exactly one Jolokia bulk POST per
node — an array of read and exec entries — never one request per attribute,
operation, or queue.

#### Scenario: Fast tick is a single POST

- **WHEN** the fast tier needs HA attributes plus `listNetworkTopology()` for a node
- **THEN** Studio sends one POST whose body is a JSON array of those entries and
  reads each entry's own status from the aligned response array

#### Scenario: A failed entry does not abort the tick

- **WHEN** one entry in a batched response has a non-200 status
- **THEN** Studio consumes the successful entries and records the failure without
  discarding the tick or skipping other nodes

### Requirement: Management calls are rate-limited per node

The system SHALL enforce a configurable ceiling on management calls per second
to each individual node, applied before every management request Studio issues
to that node — whether from a scrape tick or from an operator-initiated
operation such as a message browse or mutation. The default ceiling SHALL be
conservative so that Studio is never the reason a broker is overloaded.

#### Scenario: Bursts are shaped, not dropped

- **WHEN** Studio would exceed a node's per-second ceiling
- **THEN** the excess calls wait until capacity is available rather than failing

#### Scenario: One slow node does not stall the others

- **WHEN** one node is rate-limited or slow to respond
- **THEN** other nodes and other clusters continue to be scraped on schedule

#### Scenario: Operator-initiated calls share the ceiling

- **WHEN** an operator browses or mutates messages on a node while that node is
  being scraped
- **THEN** both the scrape and the operator call are counted against the same
  per-node per-second ceiling

### Requirement: Network I/O is outside database transactions

The system SHALL perform every broker HTTP call outside any open database
transaction. Persisting a tick's results SHALL happen in a separate short
transaction after the broker responses have been received and parsed.

#### Scenario: A hung broker holds no database transaction

- **WHEN** a node's Jolokia endpoint stops responding until the read timeout
- **THEN** no database transaction is held open for the duration of that call

### Requirement: The scrape owns the split-brain cycle counter

The system SHALL maintain the monotonic refresh-cycle counter and the
one-cycle split-brain corroboration state per cluster, advanced only by the
scrape schedule. Reading the topology or health view SHALL NOT advance either.

#### Scenario: Cycle advances once per fast tick per cluster

- **WHEN** the fast tier runs for a cluster
- **THEN** that cluster's cycle counter increments once for the tick, independent
  of other clusters

#### Scenario: Reading health does not corroborate

- **WHEN** the health view for a cluster is requested repeatedly between fast ticks
- **THEN** the split-brain status does not change until the next fast tick

### Requirement: The latest queue state is cached and stale rows are removed

The system SHALL keep a latest-state-per-queue cache, updated by upsert on every
sweep, and SHALL remove cache rows for queues that a node no longer reports once
a full sweep of that node has completed. The cache is disposable — losing it
SHALL only cost a re-scrape, never correctness.

#### Scenario: A deleted queue leaves the cache

- **WHEN** a queue that was present in the cache is absent from a completed sweep
  of its node
- **THEN** its cache row is removed after that sweep

#### Scenario: A queue count change is an update, not a churn

- **WHEN** a queue's counters change between sweeps
- **THEN** its existing cache row is updated in place, keyed by node and queue name

### Requirement: Metric samples are written and retained for a bounded window

The system SHALL append queue counter samples to the metric history on the
medium and slow tiers, and SHALL delete history older than a configurable
retention window. The default retention SHALL be seven days. History SHALL be
retained in daily partitions rather than a single unpartitioned table, so that
trimming expired history is a partition drop rather than a row-by-row delete.

#### Scenario: Old samples are trimmed

- **WHEN** a partition's contents are entirely older than the configured
  retention window
- **THEN** that partition is dropped and its samples are no longer queryable

#### Scenario: Samples written before partitioning still age out

- **WHEN** metric samples exist in the fallback partition from before daily
  partitioning began
- **THEN** those samples are still deleted once they exceed the retention
  window, via the existing row-by-row trim on that partition only

### Requirement: A node failure is isolated and recorded

The system SHALL catch any failure scraping one node, record it against that
node without propagating it, and continue scraping the remaining nodes.

#### Scenario: One unreachable node

- **WHEN** a node is unreachable during a tick
- **THEN** its last error is recorded, its previous cached data is retained, and
  every other node in every cluster is still scraped

### Requirement: Scrape cadence changes apply without a restart

The system SHALL re-read each scrape tier's configured interval before scheduling
that tier's next run, so that changing a tier interval through the settings API
takes effect on the following cycle without restarting Studio.

#### Scenario: A shortened interval speeds up the next cycle

- **WHEN** an operator shortens the fast-tier interval through the settings API
- **THEN** the fast tier's next run is scheduled at the new interval with no
  restart

#### Scenario: A lengthened interval slows the next cycle

- **WHEN** an operator lengthens the slow-tier interval
- **THEN** the slow tier's subsequent runs use the new interval
