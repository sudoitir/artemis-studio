## Purpose

Defines how Artemis Studio subscribes to a broker's `activemq.notifications`
stream over the Core protocol, turns each notification into a typed domain
event, and keeps a bounded, queryable history of those events per cluster.

## Requirements

### Requirement: Studio subscribes to notifications on every serving node

The system SHALL maintain one subscription to the `activemq.notifications`
address per serving node of every registered cluster, using the Core protocol
client. The set of subscriptions SHALL be reconciled against the live topology
so that a subscription follows failover: when a node stops serving, its
subscription is closed; when a node becomes the serving node, a subscription is
opened. A subscription failure on one node SHALL NOT prevent subscriptions on
other nodes or other clusters.

#### Scenario: Subscription opens for a live node

- **WHEN** a cluster has a live node with a reachable Core URL and notifications permitted
- **THEN** Studio holds an open subscription to `activemq.notifications` on that node

#### Scenario: Subscription follows failover

- **WHEN** the serving node of a logical node changes from the primary to the backup
- **THEN** the subscription on the former primary is closed and a subscription is opened on the backup

#### Scenario: One node's failure is isolated

- **WHEN** the subscription to one node cannot be established
- **THEN** subscriptions to the other nodes and clusters remain open and that node's failure reason is recorded

### Requirement: Reconnect is Studio-driven and does not block callers

The system SHALL drive its own reconnection for a dropped or failed Core
subscription, with a bounded exponential backoff between attempts, rather than
relying on the client library's automatic reconnect. Connection attempts SHALL
NOT consume the per-node management-call rate limiter, and a slow or unreachable
node SHALL NOT block scrape ticks or operator requests.

#### Scenario: A dropped subscription is retried with backoff

- **WHEN** an established subscription drops
- **THEN** Studio retries on an increasing interval up to a ceiling until it reconnects or the node stops serving

#### Scenario: An unreachable Core endpoint does not stall other work

- **WHEN** a node's Core endpoint is unreachable
- **THEN** scrape ticks and operator requests for that cluster continue unaffected while reconnection is retried in the background

### Requirement: Each notification is normalised to a typed domain event

The system SHALL convert every received notification into a domain event
carrying: an event type derived from `_AMQ_NotifType`, the originating cluster
and node, the notification timestamp, and — where the notification provides them
— the address, queue or routing name, and the consumer, session, connection, and
remote-address identifiers. The complete set of `_AMQ_*` properties SHALL be
retained verbatim on the event. A notification whose type is not recognised
SHALL still produce an event, marked with the unrecognised type rather than
discarded.

Each normalised event SHALL be delivered to every registered consumer of the
notification stream, not only the event-history writer. A delivery failure in
one consumer SHALL NOT prevent delivery to another consumer or to subsequent
events.

#### Scenario: A consumer-created notification becomes an event

- **WHEN** a `CONSUMER_CREATED` notification is received
- **THEN** a domain event of that type is produced with the address, consumer, session, and connection identifiers populated and the raw properties retained

#### Scenario: An unknown notification type is preserved

- **WHEN** a notification carries a `_AMQ_NotifType` value Studio does not recognise
- **THEN** an event is still produced, flagged as an unknown type, with the raw properties retained

#### Scenario: One consumer's failure does not affect another

- **WHEN** one registered consumer of the notification stream throws while handling an event
- **THEN** every other registered consumer still receives that event, and subsequent events are still delivered to all consumers

### Requirement: Event history is persisted, bounded, and reaped

The system SHALL persist domain events to a per-cluster history. Writes SHALL be
buffered and batched so that notification volume is decoupled from database
latency, and the buffer SHALL be bounded: when it is full, further events are
dropped and a per-cluster dropped-event counter is incremented and exposed
through the events API rather than the drop being silent. Persisted events SHALL
be deleted after a configurable retention window.

#### Scenario: Buffer overflow is counted, not hidden

- **WHEN** events arrive faster than the buffer can be flushed and the buffer is full
- **THEN** the excess events are dropped and the cluster's dropped-event count reported by the events API increases

#### Scenario: Old events are reaped

- **WHEN** a persisted event is older than the retention window
- **THEN** a scheduled reap removes it

### Requirement: Event history is served through a filtered paged API

The system SHALL expose a paged read of a cluster's event history, newest first,
filterable by event type, node, address, and time range. The response SHALL
include the current dropped-event count and the timestamp of the oldest retained
event.

#### Scenario: Filtered page is returned

- **WHEN** an operator requests events of a given type within a time range
- **THEN** the response contains one page of matching events, newest first, with the total match count

#### Scenario: Drop visibility travels with the data

- **WHEN** events have been dropped for a cluster
- **THEN** every events API response for that cluster reports the non-zero dropped-event count

### Requirement: The events screen states when notifications are not available

The frontend SHALL provide a routed events view for a cluster. When the
cluster's notification capability is not available, the view SHALL show why,
including the `broker.xml` change that would enable it, and SHALL NOT present an
empty list as if no events had occurred.

#### Scenario: Unavailable capability is explained

- **WHEN** an operator opens the events view for a cluster whose notifications capability is unavailable
- **THEN** the view shows the reason and the enabling configuration rather than an empty feed

#### Scenario: Live cluster shows its events

- **WHEN** an operator opens the events view for a cluster with an active subscription
- **THEN** the view lists that cluster's events, newest first, with each event's properties inspectable

### Requirement: A slow-consumer notification is routed to a consumer topic and keeps its attribution

When the broker emits its own slow-consumer notification, the system SHALL normalise it
like any other notification, SHALL retain the consumer identity the notification carries,
and SHALL publish it on the realtime stream's consumer-oriented topic rather than leaving
it unrouted. The broker's notification SHALL be treated as authoritative for slow-consumer
state on that queue, ahead of any state Studio derives itself.

If the persisted event history cannot record this event type, the type SHALL be made
recordable by a new migration; a released migration SHALL NOT be edited.

#### Scenario: The notification reaches the stream

- **WHEN** the broker emits a slow-consumer notification on a subscribed node
- **THEN** an event of that type is published on the consumer topic of the cluster's
  realtime stream

#### Scenario: The named consumer is preserved

- **WHEN** a slow-consumer notification carries the consumer's name
- **THEN** the resulting event carries that consumer identity

#### Scenario: The broker's verdict wins

- **WHEN** the broker reports a queue's consumer as slow and Studio's own derivation does
  not
- **THEN** the operator is shown the broker's verdict as the authoritative one
