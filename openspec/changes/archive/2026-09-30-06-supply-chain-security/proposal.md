## How to run this change
This change states **requirements only**. Run it in a fresh Claude session, in number order:

1. `git fetch`, then create a git worktree for this change on a new branch off `origin/main` (`.claude/rules/00-workflow.md`). Work only there, and remove it after the merge.
2. Read this proposal, its specs, the capabilities it names in `openspec/specs/`, and the ADRs they cite.
3. Brainstorm and investigate (`/opsx:explore`, `superpowers:brainstorming`); check libraries with ctx7. Ask the user only what is really theirs to decide.
4. `/opsx:update`: add `design.md`, sharpen the specs (turn ADDED into MODIFIED where a requirement changes an existing one), replace the stub `tasks.md`.
5. `/opsx:apply` with the harness under **Execution**. New decisions get an ADR.
6. Verify: `just verify`; for UI, run Studio and check screenshots (light and dark, empty and error states).
7. PR, merge on green CI, `/opsx:archive`.

## Why
Studio publishes multi-arch images and a jar, and already runs an OSV scan and CodeQL default setup. Users cannot yet verify that an image or jar came from this project's pipeline, there is no software bill of materials, no provenance, and the container image itself is not scanned in CI. `SECURITY.md` and the response to a vulnerability are not written down in enough detail to rely on.

## What Changes
- Signatures on container images and the jar, tied to the release workflow's identity so users need no key of ours.
- Signatures and provenance on the published plugin kit (the Maven Central and npm packages) and on every other released binary.
- A CycloneDX SBOM attached to the image and to the release.
- A SLSA provenance attestation for release artifacts.
- A CI container image vulnerability scan that fails on fixable critical or high findings.
- `SECURITY.md` states the disclosure process and supported versions (before stable: latest release only).
- A documented CVE response policy: triage target, advisory, patched release.

## Capabilities
### New Capabilities
- `release-integrity`: verifiable, scanned and documented release artifacts
### Modified Capabilities
- none

## Out of scope
- Reproducible builds.
- A bug bounty.
- Signing plugin jars (change 07).

## Depends on
none

## Execution
**Inline**: mostly release-pipeline and documentation work that fits one context.

## Impact
Release pipeline (CI), container build, repository security policy, documentation.
