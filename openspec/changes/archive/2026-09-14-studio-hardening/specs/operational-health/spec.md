## MODIFIED Requirements

### Requirement: Shutdown releases broker resources in order

On shutdown, the system SHALL:
1. stop accepting and delivering stream events;
2. stop background jobs and scraping, and let no scheduled job start again;
3. write buffered records that are still pending, such as broker events and captured
   messages, and acknowledge on the broker only what was written;
4. close notification subscriptions and capture drains;
5. close message-transport connections;
6. close management clients.

No job SHALL start a broker call once shutdown has begun.

#### Scenario: No broker call starts during shutdown

- **WHEN** shutdown begins while a job is scheduled to fire
- **THEN** the job does not start a new broker call, and subscriptions close before their connections do

#### Scenario: Buffered records are written before shutdown completes

- **WHEN** shutdown begins while broker events or captured messages are buffered but not yet written
- **THEN** they are written before their connections close, and a captured message that could not be written is left unacknowledged on the broker

## ADDED Requirements

### Requirement: Resource growth and broker load are observable

The system SHALL expose, on its metrics endpoint:
- its live thread count;
- the number of management requests issued per node;
- the time callers waited for that node's rate-limit capacity, and how often they gave up.

An operator SHALL be able to tell from these alone whether Studio's own resource use is
growing, and whether it is loading a broker at its ceiling.

#### Scenario: Per-node request volume is visible

- **WHEN** Studio issues management requests to a node
- **THEN** the metrics endpoint reports the count of requests issued to that node and the rate-limit wait incurred

#### Scenario: Thread count is visible

- **WHEN** the metrics endpoint is scraped
- **THEN** it reports the live thread count
