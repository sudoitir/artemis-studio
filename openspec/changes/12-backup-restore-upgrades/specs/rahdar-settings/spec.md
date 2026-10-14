## ADDED Requirements

### Requirement: Settings are included in configuration transfer
Runtime settings SHALL be part of the exported document and be applied on import subject to the same permission as writing them.

#### Scenario: Import without permission
- **WHEN** the importer lacks the settings-write permission
- **THEN** settings in the document are refused
