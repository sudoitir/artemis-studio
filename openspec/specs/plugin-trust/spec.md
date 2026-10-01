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

### Requirement: Only a verified plugin may sign users in
A plugin that declares a credential sign-in provider SHALL be activated only when its signer is a trusted key, whether or not unverified plugins are allowed. Activating a plugin version that adds a sign-in provider SHALL need explicit confirmation, and the review screen SHALL say that the plugin will receive the passwords users type for it. A running plugin whose key stops being trusted SHALL keep running, but its providers SHALL stop signing anyone in at once.

#### Scenario: An unsigned sign-in plugin with the allowance on
- **WHEN** unverified plugins are allowed and an unsigned plugin that declares a sign-in provider is activated
- **THEN** activation is refused with a reason naming the sign-in provider and the missing trusted signature

#### Scenario: Confirming a new sign-in provider
- **WHEN** a trusted plugin's install or update adds a sign-in provider
- **THEN** the review states that it will receive users' passwords, and activation without confirmation is refused

#### Scenario: A key removed while a sign-in plugin runs
- **WHEN** the key of a running plugin with a sign-in provider is removed
- **THEN** its provider stops signing users in, and operational health names the plugin as unverified

### Requirement: Trusted publisher keys can be pinned by configuration
Studio SHALL read a list of trusted publisher keys from its configuration, each with a name and a PEM certificate or public key, and SHALL make the trusted key table agree with it at every start, before any installed plugin is started. A key is identified by the same fingerprint whichever way it was added.

#### Scenario: A configured key trusts at first boot
- **WHEN** a plugin installed earlier was signed by a key that is in the configuration but not yet in the trusted key table, and Studio starts
- **THEN** the key is added with the source `CONFIGURATION` and the configured name, and the plugin starts and is verified

#### Scenario: Removing a key from configuration un-trusts it
- **WHEN** a key that was added from the configuration is no longer configured and Studio starts
- **THEN** the key is removed, the plugins it signed keep running, are shown as unverified, and operational health reports degraded

#### Scenario: An administrator's key is configured
- **WHEN** a key an administrator added is also configured
- **THEN** it becomes a configured key, named from the configuration

#### Scenario: A key is never removed from under an administrator
- **WHEN** a key an administrator added is not in the configuration
- **THEN** it stays

#### Scenario: Environment variables
- **WHEN** the keys are given as indexed environment variables
- **THEN** they bind the same as a YAML list

### Requirement: A configured key cannot be removed in the UI or API
Studio SHALL show the source of every trusted key, and SHALL refuse to remove a configured key through the API or the UI, naming the configuration property to change.

#### Scenario: Removing a configured key through the API
- **WHEN** an installer removes a configured key through the API
- **THEN** it is refused with 409 and a problem of type `configured-key` that names the property and says to remove the key there and restart, the key stays, and the refusal is audited

#### Scenario: A configured key in the dialog
- **WHEN** the Trusted keys dialog lists a configured key
- **THEN** it shows "From configuration", and Remove is visible but disabled with the reason, which a keyboard reaches

#### Scenario: Adding a configured key again
- **WHEN** an installer adds a key that is already configured
- **THEN** it is refused as already trusted

### Requirement: An invalid configured key fails startup
Studio SHALL refuse to start when a configured trusted key has no name, has no key, cannot be read, or has the same key as another entry, and SHALL name the entry's index and the reason.

#### Scenario: An invalid entry
- **WHEN** the entry at index 1 holds text that is neither a PEM certificate nor a PEM public key
- **THEN** Studio does not start, and the message names index 1 and says what was expected

#### Scenario: A missing name
- **WHEN** an entry has no name
- **THEN** Studio does not start, and the message names the entry's index

#### Scenario: Two entries with the same key
- **WHEN** two entries hold the same key
- **THEN** Studio does not start, and the message names both indexes and the fingerprint

### Requirement: Changes made from configuration are audited
Studio SHALL audit every key the reconciliation adds, converts, renames or removes, with the existing key actions, the actor `configuration` and the key's fingerprint.

#### Scenario: A configured key is added
- **WHEN** Studio starts with a key in its configuration that is not trusted yet
- **THEN** a `PLUGIN_KEY_ADD` event by `configuration` records the key's fingerprint

#### Scenario: A configured key is removed
- **WHEN** Studio starts without a key it had added from the configuration
- **THEN** a `PLUGIN_KEY_REMOVE` event by `configuration` records the key's fingerprint
