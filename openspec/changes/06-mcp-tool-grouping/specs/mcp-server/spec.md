## ADDED Requirements

### Requirement: The tool surface is discoverable without being exhaustively listed

The system SHALL present its MCP tool surface so that a client can identify the
right operation without every operation's full detail being carried in the tool
listing that is sent before any question is asked.

Discovery MAY be progressive — detail fetched when it is needed rather than
published up front — but SHALL NOT be mandatory: a client that reads only the tool
listing SHALL still be able to reach every capability the surface offers, even if
it does so less efficiently.

The system SHALL NOT present a capability it has as absent. Where the set of listed
tools is narrower than the set of capabilities, the listing SHALL name where the
remainder can be discovered, so that a client is never left to conclude that an
operation does not exist when it does.

#### Scenario: A minimal client still reaches every capability

- **WHEN** a client uses only the tool listing, without fetching any discovery
  resource
- **THEN** every capability the surface offers remains reachable

#### Scenario: A narrowed listing says where the rest is

- **WHEN** the tool listing presents fewer entries than the surface has operations
- **THEN** it names how the remaining operations are discovered

#### Scenario: Discovery does not change what is permitted

- **WHEN** a client discovers an operation through any route
- **THEN** its permission check, safety cap and audit trail are the ones the
  equivalent HTTP request would get
