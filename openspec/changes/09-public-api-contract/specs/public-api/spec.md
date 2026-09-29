## ADDED Requirements

### Requirement: The OpenAPI document is a versioned release artifact
Each release SHALL publish the OpenAPI document of that version alongside the other release artifacts.

#### Scenario: Release
- **WHEN** a version is released
- **THEN** its OpenAPI document is downloadable and matches the running API

### Requirement: Versioning and deprecation follow a documented policy
The documentation SHALL state how the API is versioned and deprecated; a deprecated endpoint SHALL send deprecation and sunset headers.

#### Scenario: Deprecated endpoint
- **WHEN** a deprecated endpoint is called
- **THEN** the response carries deprecation and sunset headers

#### Scenario: Pre-stable break
- **WHEN** a breaking change lands before the stable release
- **THEN** it is flagged in the release notes

### Requirement: Breaking API changes are detected in CI
CI SHALL compare the API document with the last release and SHALL fail on a breaking change that is not explicitly flagged.

#### Scenario: Unflagged break
- **WHEN** a pull request removes a response field without a flag
- **THEN** CI fails naming the change

### Requirement: List endpoints, errors and limits follow one convention
All list endpoints SHALL paginate the same way, all errors SHALL be problem+json, and every rate-limited endpoint SHALL send the standard limit headers.

#### Scenario: Error shape
- **WHEN** any endpoint fails
- **THEN** the body is problem+json with a stable type

#### Scenario: Pagination
- **WHEN** a list is requested
- **THEN** the page size is bounded and the next page is discoverable

### Requirement: Mutating endpoints accept idempotency keys
A mutating endpoint SHALL accept an idempotency key and SHALL return the original result for a repeat of the same key and request.

#### Scenario: Retry
- **WHEN** a client repeats a request with the same key
- **THEN** the mutation is applied once and the same result returns

#### Scenario: Key reuse with other body
- **WHEN** the same key is sent with a different request
- **THEN** it is refused

### Requirement: TypeScript and Java clients are generated and published
Each release SHALL publish generated TypeScript and Java clients that match the API document.

#### Scenario: Client release
- **WHEN** a release is published
- **THEN** both clients of that version are available

### Requirement: Contract tests keep implementation and document aligned
The build SHALL run contract tests that fail when responses differ from the document.

#### Scenario: Drift
- **WHEN** an endpoint returns a field the document lacks
- **THEN** the contract tests fail
