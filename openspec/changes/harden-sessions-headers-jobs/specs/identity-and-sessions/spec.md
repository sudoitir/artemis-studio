## ADDED Requirements

### Requirement: Cross-site requests need a CSRF token unless a bearer token authenticated them

A state-changing request SHALL carry a valid CSRF token unless a valid API bearer token authenticated it. The system SHALL reject any other `Authorization` header with 401.

#### Scenario: A junk Authorization header does not skip CSRF
- **WHEN** a POST carries the session cookie, no CSRF token and `Authorization: Basic x`
- **THEN** it is rejected with 401

#### Scenario: A valid bearer token needs no CSRF token
- **WHEN** a POST carries a valid API token and no CSRF token
- **THEN** it is processed

### Requirement: Revoking access ends sessions

The system SHALL end every session of each affected user when a user is disabled, when a user's role or grant assignments change, or when a role's permissions change.

#### Scenario: A disabled user is signed out
- **WHEN** an administrator disables a signed-in user
- **THEN** that user's next request with the old session is unauthenticated

#### Scenario: A role change applies at once
- **WHEN** an administrator removes a permission from a role
- **THEN** every member's existing session ends and their next sign-in carries the new grants

### Requirement: Studio sends a Content-Security-Policy

Every response SHALL carry a Content-Security-Policy that allows scripts only from Studio's origin, forbids plugins and framing, and restricts connections to Studio's origin. Plugin SVG assets SHALL carry `Content-Security-Policy: sandbox`.

#### Scenario: Studio cannot be framed
- **WHEN** a page from another origin frames Studio
- **THEN** the browser refuses, because of `frame-ancestors 'none'`

#### Scenario: A plugin SVG cannot run script
- **WHEN** a plugin's SVG asset is opened directly
- **THEN** the response carries `Content-Security-Policy: sandbox`
