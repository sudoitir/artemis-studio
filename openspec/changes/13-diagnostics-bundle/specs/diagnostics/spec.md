## ADDED Requirements

### Requirement: Reporting a bug pre-fills an issue and sends nothing by itself
The report action SHALL open a pre-filled issue form containing the version, environment and sanitized configuration for the user to review, and SHALL make no request to any external service until the user submits.

#### Scenario: Offline click
- **WHEN** the user opens the report action
- **THEN** the form is filled locally and no external request is made

#### Scenario: Secret in config
- **WHEN** the configuration holds a secret
- **THEN** it is absent from the pre-filled text

### Requirement: A support bundle can be downloaded
An administrator SHALL be able to download a bundle of logs, settings, a thread dump, health, the plugin list and versions.

#### Scenario: Download
- **WHEN** an administrator requests a bundle
- **THEN** a file with those sections is produced

### Requirement: The bundle is redacted
Every section of the bundle SHALL be redacted by the product's redaction rules, and tests SHALL show that known secrets do not appear.

#### Scenario: Planted secret
- **WHEN** a known secret exists in logs and settings
- **THEN** it is not in the bundle

### Requirement: The bundle can be previewed and trimmed
Before download Studio SHALL show exactly what each section contains and let the administrator exclude sections; the download SHALL contain only what was previewed.

#### Scenario: Exclude logs
- **WHEN** the administrator excludes the logs
- **THEN** the bundle has no logs

### Requirement: The bundle works offline and is admin-only and audited
Creating a bundle SHALL need no external connection, SHALL require an administrator permission and SHALL be recorded in the audit trail.

#### Scenario: Non-administrator
- **WHEN** a user without the permission requests a bundle
- **THEN** it is refused

#### Scenario: Audit
- **WHEN** a bundle is created
- **THEN** an audit event records who, when and which sections
