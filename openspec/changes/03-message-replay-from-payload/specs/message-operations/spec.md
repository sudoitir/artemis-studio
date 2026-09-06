## ADDED Requirements

### Requirement: A stored payload can be replayed onto an address

The system SHALL allow an authorized operator to replay a message from stored
content — a browsed message, a dead-letter entry, or a captured request-reply
payload — onto an address of the operator's choosing.

The destination SHALL default to the address the content originated from and SHALL
be editable before the replay is confirmed.

A replay SHALL require the same authority as sending a message. The system SHALL
NOT define a separate replay permission, because replaying is sending and a grant
meaning "may send, but only this way" is not an authority boundary.

#### Scenario: A dead-lettered message is replayed after a fix

- **WHEN** an operator replays a dead-letter entry onto its original address
- **THEN** a message carrying the stored body and application headers is enqueued
  there

#### Scenario: A replay can be redirected

- **WHEN** an operator changes the destination before confirming a replay
- **THEN** the message is enqueued on the chosen address rather than the original

#### Scenario: Sending authority is required

- **WHEN** a caller who may read messages but not send them attempts a replay
- **THEN** the replay is refused

### Requirement: A replay is a new message, not a restored one

The system SHALL treat a replay as composing and sending a new message. It SHALL
NOT represent a replay as restoring the original.

The system SHALL carry the stored body, the application-set properties, the content
type, and the correlation identifier. It SHALL NOT carry broker-owned values —
message identifier, delivery count, expiry, timestamp — nor the dead-letter
bookkeeping recording where the original was routed from, because carrying that
bookkeeping would make a fresh message misreport its own history.

The system SHALL state, where the replay is confirmed, that ordering relative to
the original is not preserved and that a duplicate delivery is possible.

#### Scenario: Broker-owned values are not carried

- **WHEN** a message is replayed
- **THEN** the enqueued message has its own broker-assigned identifier and delivery
  count, and carries none from the original

#### Scenario: Dead-letter bookkeeping is not carried

- **WHEN** a dead-letter entry is replayed
- **THEN** the enqueued message does not claim the original's dead-letter origin

#### Scenario: The operator is told what a replay is

- **WHEN** an operator confirms a replay
- **THEN** the confirmation states that a new message is being sent, that ordering
  is not preserved, and that a duplicate is possible

### Requirement: A replayed message is identifiable as one, downstream

The system SHALL attach provenance to every replayed message, carrying the
identifier of the message it was composed from, the audit event that produced the
replay, and how many times the content has been replayed.

The provenance SHALL travel with the message beyond the system, so that a consumer,
a log, or a later investigation can distinguish replayed traffic from original
traffic without access to the system's own records.

#### Scenario: A consumer can tell a replay from an original

- **WHEN** a replayed message is delivered
- **THEN** it carries provenance naming the original message and the replay that
  produced it

### Requirement: Repeated replay is counted and bounded

The system SHALL increment the replay count when replaying content that is itself a
replay, and SHALL refuse a replay that would exceed a configured maximum depth.

This prevents the case where a replayed message fails, is dead-lettered again, and
is replayed repeatedly by an operator who cannot see it is the same content
returning.

#### Scenario: A replay of a replay is counted

- **WHEN** a message that is itself a replay is replayed
- **THEN** the new message's replay count is one greater

#### Scenario: A runaway replay is refused

- **WHEN** a replay would exceed the configured maximum replay depth
- **THEN** it is refused, stating the depth and the maximum

### Requirement: Replay is refused rather than degraded when fidelity is not assured

The system SHALL replay only content it can reproduce faithfully. Where it cannot —
a body the available transport cannot reproduce, or stored content that was
truncated when it was captured — the system SHALL refuse the replay and state why,
including the configuration change that would make it possible.

The system SHALL NOT send an approximation of the stored content.

#### Scenario: A binary body over a limited transport is refused

- **WHEN** an operator replays a message with a binary body over a connection whose
  transport cannot reproduce it
- **THEN** the replay is refused, the reason is stated, and the configuration change
  that would enable it is shown

#### Scenario: Truncated stored content is not replayable

- **WHEN** an operator replays a captured payload that was truncated at capture time
- **THEN** the replay is refused and the truncation is given as the reason

### Requirement: Replaying a selection is governed by the bulk safety cap

The system SHALL support replaying a selection of messages, SHALL support previewing
such a replay without sending anything, and SHALL evaluate the selection against the
bulk safety cap, refusing it above the cap unless explicitly overridden.

A partially completed bulk replay SHALL report which messages were replayed and
which were not, identified by the message they were composed from.

#### Scenario: A bulk replay can be previewed

- **WHEN** an operator previews replaying a selection of dead-letter entries
- **THEN** the count is reported and no message is sent

#### Scenario: A large bulk replay is refused by default

- **WHEN** a selection exceeds the bulk safety cap
- **THEN** the replay is refused, stating the count and the cap, until explicitly
  overridden

#### Scenario: A partial bulk replay is legible

- **WHEN** a bulk replay succeeds for some messages and fails for others
- **THEN** the result names which originals were replayed and which failed, and why

### Requirement: The system never replays on its own

The system SHALL replay only in response to an explicit operator or client action.
It SHALL NOT offer replay triggered by a rule, a schedule, or the arrival of a
message on a dead-letter address, because such a trigger is an unbounded message
generator whose failure mode resembles a working system.

#### Scenario: Dead-lettering does not trigger a replay

- **WHEN** a message arrives on a dead-letter address
- **THEN** the system records and presents it, and replays nothing

### Requirement: A replay shows what will be sent before it is sent

Before a replay is confirmed, the system SHALL show the message that will be sent —
its destination, its body, and the headers that will be carried — and SHALL
distinguish, in that view, the values carried from the original from those the
broker will assign.

The destination SHALL be an explicit field showing the original address as its
starting value, so that redirecting a replay to a test address is a visible edit
rather than a hidden option.

Where the body is too large to show in full, the system SHALL show a bounded
excerpt and say that it is one, never a silently truncated body presented as
complete.

#### Scenario: The operator sees the message, not a description of it

- **WHEN** an operator is about to replay a message
- **THEN** the destination, body and carried headers that will be sent are shown

#### Scenario: What the broker will assign is distinguished from what is carried

- **WHEN** the replay preview is shown
- **THEN** the values that will be newly assigned are visually distinguished from
  those carried from the original

#### Scenario: A truncated preview says so

- **WHEN** a body is too large to display
- **THEN** the excerpt is marked as an excerpt

### Requirement: A refused replay explains which of its reasons applies

A replay may be refused because the transport cannot reproduce the body, because the
stored content was truncated at capture, or because the replay depth ceiling was
reached. These have different remedies, and the system SHALL name which one applies
and what the operator can do about it — enabling a transport, accepting that the
content is unrecoverable, or recognising that the message has already been round
several times.

A refusal SHALL be presented where the action was taken, and SHALL NOT be reported
only as a generic failure notification.

#### Scenario: A transport refusal offers the configuration that would fix it

- **WHEN** a replay is refused because the transport cannot reproduce the body
- **THEN** the reason names the transport and shows the configuration that would
  enable it

#### Scenario: A depth refusal explains what it means

- **WHEN** a replay is refused for exceeding the replay depth
- **THEN** the message states how many times this content has already been replayed

### Requirement: A replayed message is marked as one wherever messages are shown

Where the system displays messages, a message carrying replay provenance SHALL be
marked as a replay and SHALL link to what it was replayed from, so that an operator
browsing a queue during an incident is not misled into treating replayed traffic as
new traffic.

Replay depth SHALL be visible on that marking, because a message that has been
replayed several times is the signal that a loop is forming.

#### Scenario: Replayed traffic is identifiable while browsing

- **WHEN** an operator browses a queue containing replayed messages
- **THEN** those messages are marked as replays and link to their origin

#### Scenario: A forming loop is visible

- **WHEN** a message has been replayed more than once
- **THEN** its replay depth is shown
