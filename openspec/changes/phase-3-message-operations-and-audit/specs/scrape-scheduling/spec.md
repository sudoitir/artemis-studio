## MODIFIED Requirements

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

## ADDED Requirements

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
