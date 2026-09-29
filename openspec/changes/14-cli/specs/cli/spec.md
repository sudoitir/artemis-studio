## ADDED Requirements

### Requirement: The CLI is a single cross-platform binary released with Studio
Each Studio release SHALL publish the CLI as a single binary for Linux, macOS and Windows on amd64 and arm64, versioned with Studio.

#### Scenario: Release
- **WHEN** a version is released
- **THEN** all six binaries are attached, each with a signature and an SBOM verifiable as the release documentation describes

### Requirement: The CLI covers the everyday operations
The CLI SHALL provide commands for clusters, queues, addresses, messages (browse, send, move, purge), SQL queries, tokens, configuration export and import, and a drift check.

#### Scenario: Browse messages
- **WHEN** a user browses a queue
- **THEN** messages are listed subject to their permissions

### Requirement: Destructive commands can be previewed
`move` and `purge` SHALL support `--dry-run`, which reports the effect and changes nothing, and SHALL require an explicit confirmation flag to run.

#### Scenario: Dry run
- **WHEN** a user purges with `--dry-run`
- **THEN** the count is reported and nothing is removed

#### Scenario: No confirmation
- **WHEN** a non-interactive purge lacks the confirmation flag
- **THEN** it is refused

### Requirement: Output is machine-readable and exit codes are stable
Commands SHALL print table, JSON or YAML as chosen, and SHALL exit with documented codes that distinguish success, usage error, authentication failure, denial, not found and server error.

#### Scenario: JSON
- **WHEN** a user asks for JSON
- **THEN** the output is valid JSON only

#### Scenario: Denied
- **WHEN** Studio denies a call
- **THEN** the exit code is the documented denial code

### Requirement: Profiles and token authentication work non-interactively
The CLI SHALL keep named profiles for Studio addresses and authenticate with an API token from a flag, the environment or a profile, without prompting; a token SHALL never be printed.

#### Scenario: CI run
- **WHEN** a CI job supplies a token through the environment
- **THEN** commands run without prompts

#### Scenario: Verbose output
- **WHEN** debug output is enabled
- **THEN** the token is masked

### Requirement: Shell completion is provided
The CLI SHALL generate completion for common shells.

#### Scenario: Completion
- **WHEN** a user installs completion
- **THEN** commands and flags complete

### Requirement: The CLI uses only the published API
The CLI SHALL call only endpoints in the published API contract and SHALL report a clear error when the Studio version is incompatible.

#### Scenario: Old Studio
- **WHEN** the server lacks an endpoint the CLI needs
- **THEN** the CLI reports the required Studio version

### Requirement: The CLI verifies Studio's TLS certificate
The CLI SHALL verify Studio's certificate against the system trust store or a CA file named in the profile, and SHALL NOT turn verification off unless a flag that names the risk is given, which it SHALL warn about on every run.

#### Scenario: Private CA
- **WHEN** a profile names the CA that signed Studio's certificate
- **THEN** the connection verifies without disabling anything

#### Scenario: Wrong certificate
- **WHEN** the certificate does not verify
- **THEN** the CLI exits with the documented authentication-failure code and sends no token
