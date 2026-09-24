# plugin-messaging Specification

## Purpose
How a runtime plugin receives and sends broker messages through Studio: taps, consumers and sends that Studio owns end to end, acting for a user whose permissions it keeps checking (ADR-0111).

## Requirements

### Requirement: A plugin can tap a queue without disturbing it

The system SHALL let a plugin register a tap on a queue of a registered cluster. The tap SHALL deliver to the plugin a copy of each message routed to that queue. It SHALL NOT remove messages from the queue, change what existing consumers receive, or block or slow a producer. When the plugin cannot keep up, the tap SHALL drop the oldest copies within a stated bound, and the system SHALL report how many it dropped rather than grow without limit.

#### Scenario: Existing consumers are unaffected
- **WHEN** a plugin taps a queue that has an application consumer
- **THEN** the application consumer receives every message exactly as before, and the plugin receives a copy of each

#### Scenario: A slow plugin never blocks producers
- **WHEN** the plugin stops processing while producers keep sending
- **THEN** producers are not blocked, and the system reports the number of dropped copies

### Requirement: A plugin can consume a queue with explicit acknowledgement

The system SHALL let a plugin register a consumer on a queue. The consumer SHALL receive each message and SHALL acknowledge or reject it. A message that the plugin rejects, fails on, or does not acknowledge, including when the plugin or Studio stops, SHALL remain available for redelivery, bounded by the broker's own delivery-attempt limit.

#### Scenario: A rejected message is redelivered
- **WHEN** a plugin rejects a message it consumed
- **THEN** the broker delivers it again

#### Scenario: An unacknowledged message is redelivered
- **WHEN** Studio stops while a plugin holds a received message it has not acknowledged
- **THEN** the message is delivered again after the consumer is back

### Requirement: A plugin can send a message

The system SHALL let a plugin send a message to an address of a registered cluster, with a body, headers and properties.

#### Scenario: A sent message arrives with its headers
- **WHEN** a plugin sends a message with a header to an address
- **THEN** a consumer of that address receives the body and the header unchanged

### Requirement: Studio owns messaging resources and threads

The system SHALL own every connection, thread and broker object used for plugin messaging. It SHALL cover every node of a clustered broker, and it SHALL report per node when a node cannot be reached. It SHALL process each registration exactly once when several Studio instances share a cluster. It SHALL remove every broker object that a registration created when the registration is removed or the plugin is disabled or uninstalled. After a crash, it SHALL reclaim those objects on the next start.

#### Scenario: Uninstall leaves nothing on the broker
- **WHEN** a plugin with an active tap is uninstalled
- **THEN** no broker object created for that tap remains

#### Scenario: Two Studio instances do not double-deliver
- **WHEN** two Studio instances share a database and a cluster that has one registered consumer
- **THEN** each message is handed to the plugin once

#### Scenario: An unreachable node is reported
- **WHEN** one node of a clustered broker is down
- **THEN** the registration reports that node as not covered, and it keeps working on the other nodes

### Requirement: Plugin messaging honours cluster permissions

Every registration and send SHALL name an acting user. The system SHALL allow a tap only when that user holds `message:read` on the cluster, a consumer only with `message:read` and `queue:purge`, and a send only with `message:send`. It SHALL re-check the user's current grants on every reconciliation. When the user is removed or loses a permission, the registration SHALL be suspended: its delivery stops, its broker objects are removed, and its state names the user, the permission and the cluster. It SHALL resume by itself when the permission returns. The system SHALL refuse a registration on objects that Studio reserves for itself.

#### Scenario: A plugin cannot tap a cluster its operator cannot read
- **WHEN** a plugin registers a tap, acting for a user without access to that cluster
- **THEN** the registration is refused with a reason

#### Scenario: A lost grant suspends the registration with a reason
- **WHEN** the acting user of an active tap loses `message:read` on its cluster
- **THEN** within one reconciliation the tap stops, its broker objects are removed, and the registration reports it is suspended because that user lacks `message:read` on that cluster

#### Scenario: Studio's own objects cannot be tapped
- **WHEN** a plugin registers a tap on a queue under Studio's reserved prefix
- **THEN** the registration is refused with a reason

### Requirement: A plugin's registrations are scoped to that plugin
The system SHALL bind every messaging operation to the plugin that performs it. A plugin SHALL NOT see, change or remove another plugin's registrations, and SHALL receive deliveries only for its own.

#### Scenario: Registrations are isolated
- **WHEN** two plugins each register under the same key
- **THEN** each sees only its own registration, and each receives only its own deliveries

### Requirement: A plugin can check a send before making it

The system SHALL let a plugin ask whether a send to an address of a cluster would be allowed for a given user, without sending. The answer SHALL be the same reason a send would be refused with (a missing or too-long address, an address reserved for Studio or the broker, an unknown cluster, or a missing `message:send` permission), or none. A send SHALL apply the same check.

#### Scenario: A reserved address is reported before sending
- **WHEN** a plugin checks a send to an address under Studio's reserved prefix
- **THEN** it gets the reason the address is reserved, and nothing is sent

#### Scenario: A user without send permission is reported
- **WHEN** a plugin checks a send for a user who lacks `message:send` on the cluster
- **THEN** it gets a reason naming the missing permission

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
