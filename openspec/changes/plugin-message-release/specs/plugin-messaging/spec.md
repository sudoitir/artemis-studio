## MODIFIED Requirements

### Requirement: A plugin can consume a queue with explicit acknowledgement

The system SHALL let a plugin register a consumer on a queue. The consumer SHALL receive each message and SHALL acknowledge, reject or release it. A message that the plugin rejects, fails on, or does not acknowledge, including when the plugin or Studio stops, SHALL remain available for redelivery, bounded by the broker's own delivery-attempt limit. A message that the plugin releases SHALL return to the queue without counting as a delivery attempt, so that a release never brings it closer to the broker's dead-letter address. A tapped copy that is released SHALL be discarded, as a rejected one is.

#### Scenario: A rejected message is redelivered
- **WHEN** a plugin rejects a message it consumed
- **THEN** the broker delivers it again

#### Scenario: An unacknowledged message is redelivered
- **WHEN** Studio stops while a plugin holds a received message it has not acknowledged
- **THEN** the message is delivered again after the consumer is back

#### Scenario: A released message never spends a delivery attempt
- **WHEN** a plugin releases the same consumed message more times than the broker's delivery-attempt limit
- **THEN** the message is still on its queue, never on the dead-letter address, and its delivery count did not grow

#### Scenario: A rejected message still runs out of attempts
- **WHEN** a plugin rejects the same consumed message as many times as the broker's delivery-attempt limit
- **THEN** the broker moves it to its dead-letter address
