## ADDED Requirements

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
