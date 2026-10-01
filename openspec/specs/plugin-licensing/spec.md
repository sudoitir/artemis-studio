# plugin-licensing Specification

## Purpose
The generic license contract between Studio and a plugin that requires one: the descriptor flag, administrators uploading and removing the license file, Studio storing it as opaque bytes and showing the plugin's verdict, and the plugin API a plugin uses to read the file and report its judgement (ADR-0153).

## Requirements

### Requirement: A plugin SHALL be able to declare that it requires a license

A plugin's descriptor SHALL be able to state that the plugin requires a license. Studio SHALL show, for such a
plugin only, its license state: missing, unchecked, valid, expiring, expired, over limit or invalid.

#### Scenario: Plugin needs a license

- **WHEN** a plugin that declares a license requirement is installed with no license
- **THEN** the plugin list shows it as unlicensed

#### Scenario: Plugin needs none

- **WHEN** a plugin declares no requirement
- **THEN** Studio shows no license state for it and refuses a license upload for it

### Requirement: Admins SHALL be able to upload and remove license files

Studio SHALL accept a license file upload from a plugin installer who has signed in or stepped up within the
step-up window, SHALL store it, and SHALL let that user replace or remove it. Studio SHALL treat the file as
opaque bytes of at most 64 KiB and SHALL NOT interpret its meaning beyond what the owning plugin reports.

#### Scenario: Upload

- **WHEN** an admin uploads a license file for a plugin
- **THEN** the file is stored, its state is unchecked until the plugin reports, and the plugin is told the license changed

#### Scenario: Not permitted

- **WHEN** a user who is not an installer, or has not stepped up, uploads or removes a license
- **THEN** the request is refused and audited

#### Scenario: Oversized or malformed upload

- **WHEN** an empty file, a file over 64 KiB, or a body with a content type other than raw bytes is uploaded
- **THEN** the upload is refused with no state change

### Requirement: Studio SHALL show license validity and SHALL let plugins query it

Studio SHALL give a plugin its own stored license file with its hash, and SHALL let the plugin report its
verdict for that file: status (valid, expired, over limit, invalid), expiry, licensee and a short detail.
A verdict for a file that is no longer the stored one SHALL be ignored. Studio SHALL show the verdict to admins
and SHALL warn 30 days ahead of expiry. The stored file and the verdict SHALL survive restarts and SHALL be the
same on every replica. Studio SHALL tell the plugin on every replica when its license is uploaded, replaced or
removed.

#### Scenario: Near expiry

- **WHEN** a plugin reports a valid license that expires within 30 days
- **THEN** admins see an expiring warning on the plugin and the operational health is degraded naming the plugin

#### Scenario: Plugin queries

- **WHEN** a plugin asks for its license
- **THEN** it receives its own stored file, and the contract offers no way to read another plugin's license or report a verdict for another plugin

#### Scenario: Stale verdict

- **WHEN** a plugin reports a verdict for a file that an admin has since replaced
- **THEN** the verdict is ignored and the new file stays unchecked until the plugin reports on it

#### Scenario: Replicas agree

- **WHEN** a license is uploaded through one replica
- **THEN** every replica gives the plugin the same file, tells its copy of the plugin, and shows the same state

### Requirement: Studio SHALL tell plugins how many broker instances it manages

Studio SHALL give plugins the number of broker instances registered in the installation, as a count with no
names, so a plugin may size a license or anything else by it.

#### Scenario: Count

- **WHEN** an installation has two clusters of three and two broker nodes
- **THEN** a plugin asking for the broker instance count receives 5

### Requirement: An unlicensed plugin SHALL never degrade Studio

A missing, invalid or expired license SHALL affect only the plugin that declared it. Studio, its other plugins
and their start-up SHALL be unaffected, and a plugin that fails while reading or reporting its license SHALL
not affect any other.

#### Scenario: Invalid license at start-up

- **WHEN** Studio starts with a plugin whose license is invalid
- **THEN** Studio and the other plugins start normally and the plugin list shows the license problem

### Requirement: License data SHALL be stored and audited like other sensitive data

License uploads, replacements and removals SHALL be audited with the actor, the plugin, the file's hash and
size. License content SHALL NOT appear in logs, audit entries or error responses. Purging a plugin SHALL delete
its license; uninstalling SHALL keep it.

#### Scenario: Upload audited

- **WHEN** an admin uploads a license
- **THEN** an audit entry records the actor and plugin, and not the file content

#### Scenario: Removal audited

- **WHEN** an admin removes a license
- **THEN** an audit entry records the actor and plugin, and the plugin is told the license changed and finds none

#### Scenario: Purge

- **WHEN** a plugin is purged
- **THEN** its stored license is deleted with its other data

### Requirement: The license contract SHALL be generic

The contract, its documentation and its UI SHALL NOT refer to any specific vendor, product, plugin or license scheme.

#### Scenario: Third-party plugin

- **WHEN** a third-party plugin author reads the contract
- **THEN** they can use it for their own license scheme without Studio changes
