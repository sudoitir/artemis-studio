## ADDED Requirements

### Requirement: A disabled feature's settings are neither listed nor writable

The operational settings registry SHALL contain only settings declared by enabled modules. A setting belonging to a disabled feature SHALL NOT appear in the settings read or on the settings screen. A write or reset of such a setting SHALL be refused with a `404` problem detail stating the owning feature is disabled. A value already stored for that setting SHALL be kept, not deleted, and SHALL apply again if the feature is re-enabled.

#### Scenario: Disabled feature's settings are not listed

- **WHEN** the settings are read on an installation with a feature disabled
- **THEN** none of that feature's settings appear

#### Scenario: Writing a disabled feature's setting is refused

- **WHEN** a caller with the settings-write permission writes a setting that belongs to a disabled feature
- **THEN** the request is refused with `404`, the problem detail names the disabled feature, and no audit event records a change

#### Scenario: A stored override survives disablement

- **WHEN** a feature with an overridden setting is disabled and later re-enabled
- **THEN** the previously stored value is in effect again
