## ADDED Requirements

### Requirement: Plugin management belongs to an installer tier that roles cannot grant

The ability to install, activate, update, roll back, enable, disable, uninstall and purge plugins, and to change who holds that ability, SHALL be held by an installer tier kept separately from roles and permissions. The tier SHALL NOT be conferred by any role, including a role holding the full-access wildcard, and SHALL NOT be grantable through role or user editing.

The first installer SHALL be the administrator bootstrapped on a fresh instance, or the users the operator names in deploy-time configuration. Only an installer SHALL add or remove installers, and only with fresh authentication; each change SHALL be audited.

Membership SHALL be checked on every request, so a removal SHALL take effect on the user's next request. The system SHALL tell the client whether the current user is an installer rather than leaving the client to infer it.

#### Scenario: A full-access administrator is not an installer by default

- **WHEN** a user holding the full-access wildcard who is not an installer uploads a plugin
- **THEN** the request is refused with `403`

#### Scenario: A custom role cannot confer installing

- **WHEN** an administrator creates a role holding any permission string and assigns it to themselves
- **THEN** they still cannot install plugins

#### Scenario: Removing an installer takes effect immediately

- **WHEN** an installer is removed while their session is active
- **THEN** their next plugin request is refused
