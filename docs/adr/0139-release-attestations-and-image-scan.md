# ADR-0139: Releases are attested with GitHub artifact attestations, and pull requests scan the image

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/06-supply-chain-security`
- **Amends**: [ADR-0042](0042-calver-releases-on-docker-hub.md) — attestation is no longer deferred;
  [ADR-0102](0102-the-plugin-api-is-published-to-central-and-npm.md) — the Central artifacts are attested

## Context

A release pushes a multi-arch image to Docker Hub, a runnable jar to the GitHub release, the plugin
API to Maven Central (GPG-signed) and the plugin SDK to npm (with npm provenance). Nobody could check
that an image or jar came from this repository's pipeline, there was no SBOM, and the image was never
scanned. ADR-0042 turned buildkit's SBOM and provenance off because they add `unknown/unknown` entries
to the tag's platform list on Docker Hub, and deferred attestation until there were verifiers.

## Decision

- **Every released artifact is attested with `actions/attest`**, keyless: a Sigstore bundle signed
  with a short-lived certificate for `sudoitir/artemis-studio/.github/workflows/ci.yml`. The image
  and the jar get SLSA provenance and a CycloneDX SBOM attestation; the jar, sources, javadoc and pom
  deployed to Central get SLSA provenance. The check is
  `gh attestation verify <artifact> --repo sudoitir/artemis-studio --signer-workflow sudoitir/artemis-studio/.github/workflows/ci.yml`.
- **Image attestations are pushed to Docker Hub as OCI referrers**, next to the image and outside its
  platform list. Buildkit's own `provenance` and `sbom` stay off.
- **SBOMs are CycloneDX JSON from Syft**, one for the image and one for the jar, attached to the
  GitHub release with the jar's provenance bundle (`.intoto.jsonl`).
- **The release job verifies what it published** with the documented command, so a broken
  verification path fails the release instead of reaching users.
- **Pull requests scan the image with Grype**, run from its image pinned by digest. One run reports
  every finding; a second, `--only-fixed --fail-on high`, fails the build on a fixable high or
  critical and names the fixed version. Findings with no fix are reported and do not fail.
- npm provenance already covers the SDK; it is documented, not changed.

## Consequences

- Users verify every artifact with one tool and no key of ours; the signer identity is the workflow
  file, so a package published by hand has no valid attestation.
- A base-image or dependency CVE with a fix blocks the next pull request that touches the image
  inputs until the digest or version is bumped.
- An image nobody rebuilds is not rescanned; the weekly OSV run covers the manifests only.
- The release depends on Sigstore's public-good instance and GitHub's attestation API being up.

## Alternatives considered

- **`cosign sign` plus `slsa-github-generator`**: two tools and two verification stories for what
  one action does; attestations are readable by `cosign verify-attestation` anyway.
- **Buildkit `provenance: mode=max` and `sbom: true`**: unsigned, SPDX rather than CycloneDX, and the
  platform-list clutter ADR-0042 avoided.
- **Trivy**: its GitHub Action's tags were hijacked in 2026; Grype pairs with Syft, which already
  writes the SBOMs.
