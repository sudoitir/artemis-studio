## ADDED Requirements

### Requirement: Secret provider and rotation state are visible to administrators
Studio SHALL show a user with the settings-read permission the active secret provider, the current key version, the key versions available, how many stored secrets each version protects, and the last rotation with its progress and result. It SHALL never show or return key material. A user with the settings-write permission SHALL be able to start a rotation there after re-authenticating.

#### Scenario: Status view
- **WHEN** an administrator opens the security settings
- **THEN** provider, key versions and the last rotation are shown and no key is

#### Scenario: No rotation yet
- **WHEN** no rotation has ever run
- **THEN** the view says so and offers a rotation only if a newer key version is available

#### Scenario: Rotation started from the UI
- **WHEN** an administrator starts a rotation and their authentication is not fresh
- **THEN** the UI asks them to re-authenticate, then starts the rotation and shows its progress until it ends

#### Scenario: Rotation failed
- **WHEN** a rotation fails
- **THEN** the view shows the failure and the store it stopped at, without any secret or key
