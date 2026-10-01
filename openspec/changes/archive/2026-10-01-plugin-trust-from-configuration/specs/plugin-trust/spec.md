## ADDED Requirements

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
