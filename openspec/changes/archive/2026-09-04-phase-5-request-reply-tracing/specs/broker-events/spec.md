## MODIFIED Requirements

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
