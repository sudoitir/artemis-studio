## MODIFIED Requirements

### Requirement: A plugin is one self-describing artifact

A plugin SHALL be delivered as a single jar containing a descriptor that states:
- its identifier, name, version, vendor and description
- its change notes
- its base package and root configuration
- the extension-contract version it was built against, and the range of Studio versions it supports (a minimum, and optionally a maximum)
- the other features it requires
- whether it has a UI
- whether activating it needs a restart
- an optional update URL
- the permissions, setting keys, stream topics and assistant tools it contributes
- whether it requires a license

The system SHALL read the descriptor without executing any code from the plugin.

A plugin identifier SHALL consist of at least two lowercase kebab segments, SHALL be at most 50 characters, and SHALL NOT begin with `identity-`.

#### Scenario: The descriptor is read without running plugin code
- **WHEN** a jar whose classes have static initialisers is inspected
- **THEN** its descriptor and contributions are reported and none of its code has run

#### Scenario: A single-segment identifier is refused
- **WHEN** a plugin declares the identifier `notes`
- **THEN** it is refused with a message that the identifier needs a vendor segment, for example `acme-notes`

#### Scenario: A license requirement is declared
- **WHEN** a plugin's descriptor states that it requires a license
- **THEN** the review before activation and the plugin list say so
