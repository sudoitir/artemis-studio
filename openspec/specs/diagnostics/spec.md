# diagnostics Specification

## Purpose
How a user reports a bug and an administrator collects a support bundle without anything leaving the installation unasked: the pre-filled bug report, and the redacted, previewed and trimmed bundle of logs, settings, health, thread dump and plugins, admin-only and audited (ADR-0146).

## Requirements

### Requirement: Reporting a bug pre-fills an issue and sends nothing by itself
Every signed-in user SHALL be able to open a "Report a bug" form. The form SHALL be pre-filled with the Studio version, the plugin contract, Java, the operating system, the database, the sign-in providers, the installed plugins with their versions, and the browser. The environment facts SHALL come from Studio itself, redacted. Studio SHALL make no request to any external service. The issue SHALL be opened on GitHub only when the user clicks to open it, and the text SHALL also be copyable for an installation without internet access.

#### Scenario: Offline click
- **WHEN** the user opens the report action
- **THEN** the form is filled from Studio alone and no external request is made

#### Scenario: Secret in config
- **WHEN** the configuration holds a secret
- **THEN** it is absent from the pre-filled text

#### Scenario: Any signed-in user
- **WHEN** a user without administrator permissions opens the report action
- **THEN** the pre-filled form is shown

### Requirement: A support bundle can be downloaded
An administrator SHALL be able to download a zip file holding the sections `about` (versions and environment), `settings`, `health`, `threads` (a thread dump), `plugins` and `logs` (the most recent log lines kept in memory).

#### Scenario: Download
- **WHEN** an administrator requests a bundle
- **THEN** a zip file with one entry per section is produced

### Requirement: The bundle is redacted
Every section of the bundle SHALL be redacted by the product's redaction rules, and tests SHALL show that known secrets do not appear.

#### Scenario: Planted secret
- **WHEN** a known secret exists in logs and settings
- **THEN** it is not in the bundle

#### Scenario: Message content
- **WHEN** logs or state hold message bodies or property values
- **THEN** the bundle contains none of them, whatever the governance policy

### Requirement: The bundle can be previewed and trimmed
Before download Studio SHALL show exactly what each section contains, with the number of redacted values, and SHALL let the administrator exclude sections. The download SHALL be written from the previewed snapshot and SHALL contain only the sections that were kept. The snapshot SHALL expire after ten minutes and SHALL be usable only by the administrator who prepared it.

#### Scenario: Exclude logs
- **WHEN** the administrator excludes the logs
- **THEN** the bundle has no logs

#### Scenario: Download matches preview
- **WHEN** new log lines are written between preview and download
- **THEN** the downloaded logs are the previewed ones

#### Scenario: Expired or foreign snapshot
- **WHEN** a download names a snapshot that expired or that another user prepared
- **THEN** it is refused as not found

### Requirement: The bundle works offline and is admin-only and audited
Creating a bundle SHALL need no external connection, SHALL require the `diagnostics:bundle` permission and SHALL be recorded in the audit trail.

#### Scenario: Non-administrator
- **WHEN** a user without the permission requests a bundle
- **THEN** it is refused

#### Scenario: Audit
- **WHEN** a bundle is created
- **THEN** an audit event records who, when and which sections
