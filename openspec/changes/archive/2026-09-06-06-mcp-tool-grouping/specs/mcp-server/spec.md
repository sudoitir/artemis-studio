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

### Requirement: Progressive detail is reachable without optional protocol features

The primary route to the detail the tool schemas omit SHALL be one that every MCP
host implements. Resources are an optional server capability that a client is never
obliged to read, so the system SHALL NOT depend on a resource being fetched for its
surface to be usable.

The system SHALL expose that detail as a tool, returning an index of the operations
the surface offers when asked for no particular topic, and one tool's accepted
values, body shapes and semantics when asked for that tool.

Where the same detail is also published as a resource, that resource SHALL be
generated from the same source as the tool, and SHALL be a mirror on which nothing
depends.

#### Scenario: A host that never reads resources still gets the detail

- **WHEN** a client fetches no resource at all
- **THEN** it can still obtain every operation's accepted values and body shapes
  through the tool surface alone

#### Scenario: A rejected argument names the discovery route

- **WHEN** a call is rejected because a discriminator or required argument was
  wrong
- **THEN** the rejection names both the values it would have accepted and where the
  full detail can be read

### Requirement: Tools group only within one posture and one target

Operations SHALL be presented as one tool only when they share both a single honest
set of tool annotations and a single target with a common argument core.

A tool's annotations SHALL describe every operation it can perform, so a tool that
can destroy data SHALL declare itself destructive. Consequently a read-only
operation SHALL NOT be reachable through a tool that can also destroy data, because
a host gates a whole tool on that declaration.

A tool SHALL NOT present operations whose arguments are disjoint such that its
schema cannot be used without first fetching discovery detail, because that makes
progressive discovery mandatory.

#### Scenario: A read is not hidden behind a destructive tool

- **WHEN** an operation only reads
- **THEN** it is reachable through a tool that declares itself read-only

#### Scenario: A tool's annotations cover its most dangerous operation

- **WHEN** a tool can perform an operation that destroys data
- **THEN** it declares itself destructive, whatever else it can also do

### Requirement: The described surface is generated, never restated by hand

Every description of the tool surface the system publishes — the tool schemas, the
discovery tool, any discovery resource, and the instructions given to a host at
initialisation — SHALL be derived from one catalogue.

The system SHALL fail its build when a registered tool is absent from that
catalogue, so that a description of the surface cannot silently disagree with the
surface.

#### Scenario: A new tool cannot ship undescribed

- **WHEN** a tool is registered but not present in the catalogue
- **THEN** the build fails

#### Scenario: The initialisation instructions match the registered tools

- **WHEN** a host reads the instructions given at initialisation
- **THEN** every registered tool is named there
