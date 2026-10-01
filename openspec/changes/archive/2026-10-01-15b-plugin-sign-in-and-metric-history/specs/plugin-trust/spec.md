## ADDED Requirements

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
