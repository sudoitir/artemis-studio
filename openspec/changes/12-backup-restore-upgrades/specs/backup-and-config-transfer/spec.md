## ADDED Requirements

### Requirement: Backup and restore are documented and tested
The documentation SHALL describe backing up and restoring the Studio database including plugin schemas, and CI SHALL restore a backup into a fresh instance and check that Studio starts and serves its data.

#### Scenario: Restore test
- **WHEN** CI restores a backup
- **THEN** Studio starts and shows the restored clusters, roles and plugin data

### Requirement: Configuration can be exported as a versioned document
Studio SHALL export clusters with their current declarations, environments, roles and group mappings, alert rules, notification channels and settings as a document that states its format version. Secrets SHALL be left out and listed as needing re-entry, unless the administrator supplies a key for the target, in which case they SHALL be encrypted to that key.

#### Scenario: Export
- **WHEN** an administrator exports
- **THEN** the document lists the configuration and contains no readable secret

#### Scenario: Export without permission
- **WHEN** a caller without the global settings-read permission exports
- **THEN** it is refused, and a permitted export is audited

### Requirement: Configuration import has a dry run
Import SHALL offer a dry run that reports what would be created, changed or rejected, and SHALL apply nothing until confirmed; a document of an unknown format version SHALL be refused.

#### Scenario: Dry run
- **WHEN** a document is imported as a dry run
- **THEN** the changes are listed and nothing is stored

#### Scenario: Hostile document
- **WHEN** a document grants a role more than the importer may
- **THEN** it is refused

#### Scenario: Audit
- **WHEN** an import is applied
- **THEN** it is audited with its summary

### Requirement: Migrations are safe across a rolling upgrade
Each database migration SHALL work with both the previous and the new release running together, by expanding first and contracting in a later release.

#### Scenario: Mixed versions
- **WHEN** a CI upgrade test runs the previous release and the new one against the migrated schema
- **THEN** both serve reads and writes on every screen the test covers, and the test fails otherwise

### Requirement: An upgrade and rollback guide exists
The documentation SHALL describe how to upgrade, what to back up first and how to roll back.

#### Scenario: Rollback
- **WHEN** a user follows the rollback steps after an upgrade
- **THEN** the previous release runs on the pre-upgrade backup
