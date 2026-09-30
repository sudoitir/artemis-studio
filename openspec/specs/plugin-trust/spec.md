# plugin-trust Specification

## Purpose
Who stands behind a runtime plugin: signed plugin jars, the publisher keys administrators trust, the audited allowance for unverified plugins, and how install, update, review and health use that trust decision (ADR-0141).

## Requirements

### Requirement: Administrators manage trusted publisher keys
Studio SHALL let an administrator with the installer tier add and remove trusted publisher keys, showing each key's fingerprint and name.

#### Scenario: Add a key
- **WHEN** an installer adds a key
- **THEN** it is listed with its fingerprint and the action is audited

#### Scenario: Remove a key
- **WHEN** a key is removed
- **THEN** installed plugins it signed are shown as unverified at once, in the plugin list and in operational health, and their next update is refused unless signed by a trusted key

#### Scenario: A non-installer adds a key
- **WHEN** a user who is not an installer, or whose authentication is not fresh, adds a key
- **THEN** it is refused and audited

### Requirement: A plugin's signature is verified before install and update
Studio SHALL verify a plugin's signature against the trusted keys before storing or activating it, and SHALL refuse a jar whose signature is missing, invalid or from an untrusted key.

#### Scenario: Valid and trusted
- **WHEN** the signature is valid and the key trusted
- **THEN** installation proceeds

#### Scenario: Altered jar
- **WHEN** the jar was changed after signing
- **THEN** it is refused before anything is stored

#### Scenario: Signed content swapped
- **WHEN** a file inside a signed jar, such as a UI asset, is replaced or added after signing
- **THEN** the jar is refused

#### Scenario: File removed from a signed jar
- **WHEN** a file the signature covers is missing from the jar
- **THEN** the jar is refused

#### Scenario: Several signers
- **WHEN** entries in the jar are signed by different keys, or some are unsigned
- **THEN** the jar is refused

#### Scenario: Unknown signer
- **WHEN** the signer's key is not trusted
- **THEN** activation is refused and the review screen names the fingerprint and the certificate subject

#### Scenario: Trust the key from the review
- **WHEN** an installer with fresh authentication trusts the named key from the review screen, confirming they compared the fingerprint
- **THEN** the key taken from the uploaded jar is added and audited, and the review re-plans as trusted

#### Scenario: Key removed between review and activation
- **WHEN** the signer's key is removed after the review and before activation
- **THEN** activation is refused

#### Scenario: Rollback to an unverified version
- **WHEN** an installer rolls back to a previous version whose signer is not trusted and the allowance is off
- **THEN** the rollback is refused

### Requirement: Unverified plugins are visible in operational health
Studio SHALL report operational health as degraded while any installed plugin is unverified, naming those plugins.

#### Scenario: A key is removed
- **WHEN** an installed plugin's signing key is removed
- **THEN** the `studio` health group reports degraded and names the plugin

### Requirement: Unverified plugins need an explicit, audited allowance
An administrator MAY allow unverified plugins through a setting that is off by default; while it is on, an unverified plugin SHALL install, be marked with a visible "unverified" badge, and each such install SHALL be audited.

#### Scenario: Default
- **WHEN** the setting is off
- **THEN** an unsigned plugin cannot be installed

#### Scenario: Allowed
- **WHEN** the setting is on and an unsigned plugin is installed
- **THEN** it runs, shows the badge and an audit event records the decision

#### Scenario: Turning the allowance on
- **WHEN** someone turns the allowance on
- **THEN** only an installer with fresh authentication can, and the change is audited

### Requirement: The review screen shows who stands behind a plugin
Before activation the review screen SHALL show the publisher, the key fingerprint, the requested permissions and, for an update, which permissions were added or removed.

#### Scenario: Update adds a permission
- **WHEN** an update requests a new permission
- **THEN** the diff highlights it and activation needs explicit confirmation

#### Scenario: Update signed by another trusted key
- **WHEN** an update is signed by a trusted key other than the installed version's
- **THEN** the review shows the old and new fingerprints and activation needs explicit confirmation

#### Scenario: Confirmation skipped
- **WHEN** a client activates a plan that needs confirmation without confirming it
- **THEN** the server refuses the activation

### Requirement: Plugin authors can sign from the kit
The plugin template and SDK tooling SHALL provide a documented way to sign a plugin build.

#### Scenario: Signed build
- **WHEN** an author runs the documented signing step
- **THEN** the resulting jar verifies against the author's published key

#### Scenario: Verify against the published key
- **WHEN** an author runs the plugin verifier with their published certificate on a jar signed by another key, or on an unsigned jar
- **THEN** the verifier fails
