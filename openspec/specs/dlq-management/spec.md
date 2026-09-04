# dlq-management Specification

## Purpose

Defines the dead-letter management view: how Artemis Studio learns which
addresses are dead-letter and expiry targets for a cluster, how it presents the
queues on those addresses across nodes, and how an operator replays messages out
of them safely.

## Requirements

### Requirement: Dead-letter and expiry addresses are discovered from broker settings

The system SHALL determine a cluster's dead-letter and expiry addresses by
reading the broker's address settings (`getAddressSettingsAsJSON`), never by
matching queue or address names against a pattern. When the address settings
cannot be read, the view SHALL say so explicitly rather than fall back to a
name heuristic.

#### Scenario: Addresses come from settings

- **WHEN** the DLQ view is opened for a cluster whose address settings define a
  dead-letter address
- **THEN** that address and its queues are listed

#### Scenario: Unreadable settings are stated, not guessed

- **WHEN** the broker's address settings cannot be read
- **THEN** the view reports that the dead-letter configuration is unavailable and
  lists nothing inferred from names

### Requirement: DLQ queues are shown with per-node depth

The system SHALL list, for each discovered dead-letter or expiry address, the
queues on that address with their message depth per logical node, drawn from the
cached queue state.

#### Scenario: Depth per node

- **WHEN** a dead-letter queue exists on two nodes of a logical node
- **THEN** the row shows the queue's depth for each node

### Requirement: Replay from a DLQ is preview-gated and capped

The system SHALL offer replaying the messages of a dead-letter queue back to
their original addresses as a by-filter retry, subject to the same dry-run
preview, bulk cap, and audit rules as any other bulk mutation. The view SHALL
also link to the message browser for that queue.

#### Scenario: Replay all runs a previewed retry

- **WHEN** an operator chooses "replay all" on a dead-letter queue
- **THEN** a dry-run count is shown first and the retry is audited when run

#### Scenario: Replay over the cap requires override

- **WHEN** the replay count exceeds the bulk cap
- **THEN** the operator must confirm the override exactly as for any other bulk
  mutation
