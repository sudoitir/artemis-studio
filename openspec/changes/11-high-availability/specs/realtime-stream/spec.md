## ADDED Requirements

### Requirement: A client sees the same events whichever replica serves it
Events produced on one replica SHALL reach clients connected to any replica, and a reconnecting client SHALL be able to replay missed events from a different replica.

#### Scenario: Event on another replica
- **WHEN** a change is detected by replica A
- **THEN** clients on replica B receive it

#### Scenario: Reconnect elsewhere
- **WHEN** a client reconnects to another replica
- **THEN** it replays what it missed

### Requirement: Shutdown drains event streams
On shutdown a replica SHALL tell its stream clients to reconnect and SHALL stop accepting new streams before closing.

#### Scenario: Rolling restart
- **WHEN** a replica is stopped
- **THEN** its clients reconnect to another replica without losing events
