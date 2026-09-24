## ADDED Requirements

### Requirement: A plugin can check a send before making it

The system SHALL let a plugin ask whether a send to an address of a cluster would be allowed for a given user, without sending. The answer SHALL be the same reason a send would be refused with (a missing or too-long address, an address reserved for Studio or the broker, an unknown cluster, or a missing `message:send` permission), or none. A send SHALL apply the same check.

#### Scenario: A reserved address is reported before sending
- **WHEN** a plugin checks a send to an address under Studio's reserved prefix
- **THEN** it gets the reason the address is reserved, and nothing is sent

#### Scenario: A user without send permission is reported
- **WHEN** a plugin checks a send for a user who lacks `message:send` on the cluster
- **THEN** it gets a reason naming the missing permission
