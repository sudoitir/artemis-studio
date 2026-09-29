## ADDED Requirements

### Requirement: Release images and jars carry a verifiable signature
Every released container image and jar SHALL be signed keylessly, and the documentation SHALL show how to verify the signature and the expected identity.

#### Scenario: Verify an image
- **WHEN** a user verifies a released image with the documented command
- **THEN** verification succeeds and names this project's release workflow as signer

#### Scenario: Tampered artifact
- **WHEN** an artifact was altered after release
- **THEN** verification fails

### Requirement: Every release ships an SBOM
Each release SHALL attach a CycloneDX SBOM to the container image and to the release page.

#### Scenario: SBOM present
- **WHEN** a release is published
- **THEN** the image and the release both carry an SBOM listing its components

### Requirement: Every release ships a provenance attestation
Each release SHALL include a SLSA provenance attestation stating the source revision and build that produced each artifact.

#### Scenario: Provenance check
- **WHEN** the attestation is verified
- **THEN** it matches the artifact digest and the tagged source

### Requirement: CI fails on fixable serious image vulnerabilities
The pipeline SHALL scan the built container image and SHALL fail when a critical or high vulnerability has a fix available; findings without a fix SHALL be reported and not fail the build.

#### Scenario: Fixable critical
- **WHEN** the scan finds a fixable critical
- **THEN** the build fails and names the package and fixed version

#### Scenario: Unfixable finding
- **WHEN** the finding has no fix
- **THEN** the build passes and the finding is reported

### Requirement: Security policy states disclosure and supported versions
`SECURITY.md` SHALL state how to report a vulnerability confidentially, which versions are supported, and what the reporter can expect.

#### Scenario: Reading the policy
- **WHEN** a reporter reads `SECURITY.md`
- **THEN** the contact channel, supported versions and response expectations are stated

### Requirement: A CVE response policy is documented and followed
The documentation SHALL state the triage target time, how advisories are published and how a patched release is cut.

#### Scenario: Advisory published
- **WHEN** a confirmed vulnerability is fixed
- **THEN** an advisory and a patched release are published under the stated policy
