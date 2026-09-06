## ADDED Requirements

### Requirement: A captured request payload can be replayed

Where the system has captured the payload of a request-reply flow, it SHALL offer
replaying that request, so that a flow which failed can be reproduced deliberately
once its cause has been addressed.

The system SHALL record, at capture time, whether a payload was truncated by the
capture limit, and SHALL refuse to replay a truncated payload rather than sending a
prefix of the original.

A replayed request SHALL carry its correlation identifier so that the resulting flow
is traced like any other, and SHALL carry provenance identifying it as a replay of a
specific earlier flow.

#### Scenario: A failed flow is reproduced after a fix

- **WHEN** an operator replays the captured request of a flow that went unanswered
- **THEN** a new request is sent and appears as a new traced flow

#### Scenario: A truncated capture is not replayable

- **WHEN** an operator replays a request whose payload was truncated at capture
- **THEN** the replay is refused and the truncation is given as the reason

#### Scenario: The replayed flow is linked to the original

- **WHEN** a replayed request produces a new flow
- **THEN** that flow identifies the earlier flow it was replayed from
