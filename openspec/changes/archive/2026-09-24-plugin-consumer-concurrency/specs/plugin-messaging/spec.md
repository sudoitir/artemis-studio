## ADDED Requirements

### Requirement: Plugin consumers set their concurrency and get flow control

A consume registration SHALL state how many messages it processes at once per serving node (1 to 32). Studio SHALL open that many consumers per node. Each consumer SHALL hold at most one unacknowledged message, so a slow plugin receives fewer messages and the rest stay on the queue. A tap registration SHALL have a concurrency of exactly 1. Any other value SHALL be refused, and the refusal SHALL give the reason.

#### Scenario: Concurrency runs handlers in parallel
- **WHEN** a consume registration with concurrency 4 receives 4 messages whose handling blocks
- **THEN** all 4 handlers run at the same time

#### Scenario: A slow consumer is not flooded
- **WHEN** a consume registration with concurrency 3 stops returning from its handler while 100 messages wait
- **THEN** at most 3 messages per node are delivered and unacknowledged, and the other 97 stay on the queue

#### Scenario: Invalid concurrency is refused
- **WHEN** a plugin registers a consumer with concurrency 0 or 33, or a tap with concurrency 2
- **THEN** the registration is refused with the reason

#### Scenario: A changed concurrency takes effect
- **WHEN** a plugin registers the same key again with another concurrency
- **THEN** its consumers on each node are restarted with the new count

### Requirement: Message groups stay in order across concurrent consumers

When a consume registration has a concurrency above 1, messages that carry the same message group id SHALL be handled one at a time and in order on each node, as the broker's message grouping provides.

#### Scenario: Group order kept at concurrency above 1
- **WHEN** messages 1, 2 and 3 of group G reach a registration with concurrency 4, and the handling of message 1 is slow
- **THEN** message 2 is handled only after message 1 is settled, and message 3 only after message 2

### Requirement: Plugin consumers cannot starve Studio

Plugin message handling SHALL run on a bounded thread pool of its own. Plugin handlers that block SHALL NOT delay message capture, operator sessions, or other Core client work in Studio.

#### Scenario: A blocked plugin does not stall capture
- **WHEN** a plugin handler blocks every message at the full concurrency of several registrations
- **THEN** message capture on the same cluster keeps storing copies without delay
