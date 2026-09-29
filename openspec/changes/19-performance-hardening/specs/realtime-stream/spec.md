## ADDED Requirements

### Requirement: A slow event-stream client cannot grow Studio's memory
Studio SHALL bound the buffered events per subscriber. A subscriber that falls behind SHALL have its pending change signals coalesced, and one that stays behind beyond a bound SHALL be dropped and told to reconnect and replay.

#### Scenario: A client stops reading
- **WHEN** a subscriber reads far slower than events arrive
- **THEN** its buffer stays within the bound and memory use does not grow with time

#### Scenario: A dropped client recovers
- **WHEN** a subscriber is dropped for lag
- **THEN** it reconnects and receives the replay it missed, or a full-refresh signal when the gap is too large

#### Scenario: Abuse by many slow clients
- **WHEN** many subscribers stall at once
- **THEN** Studio's memory stays bounded and other subscribers are unaffected
