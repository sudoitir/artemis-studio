## ADDED Requirements

### Requirement: Indexing a backlog has a bounded broker cost per tick

When sampled indexing starts on a queue that already holds messages, the system SHALL index
that backlog. It SHALL read the backlog over as many ticks as needed, reading no more than
a bounded number of pages from each node in any one tick, and never walk the whole backlog
in a single tick.

While a backlog is still being walked, the subscription SHALL say that indexing of existing
messages is in progress. It SHALL NOT claim to be up to date.

#### Scenario: A deep backlog is walked over several ticks

- **WHEN** sampled indexing starts on a queue holding far more messages than one tick's page bound
- **THEN** each tick reads at most the page bound from that node, and successive ticks continue from where the previous one stopped until the backlog is indexed

#### Scenario: Backlog progress is stated

- **WHEN** an operator views a subscription whose backlog has not been fully indexed
- **THEN** it states that existing messages are still being indexed
