## ADDED Requirements

### Requirement: The plugin vault stores entries under the envelope scheme
Plugin vault entries SHALL be envelope encrypted like every other secret, and the contract a plugin uses to read and write them SHALL not change.

#### Scenario: Plugin reads its secret
- **WHEN** a plugin reads a vault entry
- **THEN** it receives the plaintext through the unchanged contract
