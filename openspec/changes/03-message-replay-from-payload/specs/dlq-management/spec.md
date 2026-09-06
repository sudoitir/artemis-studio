## ADDED Requirements

### Requirement: A dead-letter entry can be replayed from its stored content

The dead-letter views SHALL offer replaying an entry from its stored content, in
addition to the broker-side retry that returns a message still present in the
dead-letter queue to its original address.

The two SHALL be distinguishable to the operator: a broker-side retry moves the
existing message, while a replay sends a new message composed from stored content
and is available even when the original is no longer in the queue.

Replaying a selection of dead-letter entries SHALL be supported, previewable, and
subject to the bulk safety cap.

#### Scenario: An entry is replayed after the original is gone

- **WHEN** an operator replays a dead-letter entry whose original message is no
  longer in the queue
- **THEN** a new message composed from the stored content is enqueued on the chosen
  address

#### Scenario: Retry and replay are not presented as the same action

- **WHEN** an operator views a dead-letter entry
- **THEN** the broker-side retry and the replay are offered as distinct actions with
  their difference stated

#### Scenario: Clearing an incident is a bulk action

- **WHEN** an operator selects several dead-letter entries and replays them
- **THEN** the replay is previewed, capped, and reports its outcome per original
  entry
