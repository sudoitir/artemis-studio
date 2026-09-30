## Context

The `release` job in `.github/workflows/ci.yml` tags the merge commit, pushes a multi-arch image to
Docker Hub (`sudoit1/artemis-studio`) and creates a GitHub release with the runnable jar and its
SHA-256. `publish-api` deploys the plugin API to Maven Central, GPG-signed; `publish-sdk` publishes
`@artemis-studio/plugin-sdk` to npm through trusted publishing with `--provenance`. The image build
sets `provenance: false` and `sbom: false` because buildkit's attestations add `unknown/unknown`
entries to the tag's index (ADR-0042). Pull requests build the image (`image` job) but do not scan it.

## Goals / Non-Goals

**Goals:**
- One keyless signing mechanism for every released artifact, verified with one documented command.
- The check that the commands work runs in the release job itself.

**Non-Goals:**
- Cosign-native signatures (`cosign sign`) next to attestations; `cosign verify-attestation` reads
  the same bundles.
- Scanning the published image on a schedule (see Risks).

## Decisions

1. **GitHub artifact attestations (`actions/attest`)** for signatures, SLSA provenance and SBOM
   attestations. Each attestation is a Sigstore bundle signed with a short-lived Fulcio certificate
   for `sudoitir/artemis-studio/.github/workflows/ci.yml`; users verify with
   `gh attestation verify … --repo sudoitir/artemis-studio --signer-workflow …`. A changed artifact
   has another digest and fails. Alternatives: `cosign sign` plus a separate provenance generator
   (`slsa-github-generator`) is two tools and two verification stories; buildkit attestations clutter
   the Hub tag view and are unsigned.
2. **Image attestations are pushed to Docker Hub as OCI referrers** (`push-to-registry: true`), so
   they travel with the image and stay out of the tag's platform list. Buildkit's `provenance` and
   `sbom` stay off.
3. **CycloneDX SBOMs from Syft (`anchore/sbom-action`)**, one for the image (OS packages and the
   jar's libraries) and one for the jar. Each is attested and attached to the GitHub release.
4. **The jar's provenance bundle is a release asset** (`artemis-studio-<v>.jar.intoto.jsonl`), so the
   jar verifies offline (`--bundle`) and Scorecard's Signed-Releases check sees it.
5. **Plugin kit**: `publish-api` attests the jar, sources, javadoc and pom it deploys (GPG stays; Central
   requires it). npm provenance already names repo, commit and workflow; only its check
   (`npm audit signatures`) is documented.
6. **Grype scans the PR image**, run from its image pinned by digest (the `actionlint` pattern). Two
   runs share one DB cache: a report of every finding (never fails) and a gate
   `--only-fixed --fail-on high`, whose table names the package and the fixed version. Trivy was
   rejected: its action's tags were hijacked in 2026, and Grype pairs with Syft.
7. **Policy lives in `SECURITY.md`**, and the verification guide in the site
   (`site/src/guide/verify-releases.md`). ADR-0139 records decisions 1-6 and amends ADR-0042.

## Risks / Trade-offs

- [A CVE lands against an unchanged image] → the gate runs only when image inputs change. The
  weekly OSV run covers the manifests; a scheduled image scan can follow if that is not enough.
- [Docker Hub rejects OCI referrers] → the release job's own verify step fails loudly on the first
  release; attestations are still in GitHub's store, so `gh attestation verify oci://…` keeps working.
- [The gate goes red on a base-image CVE nobody introduced] → fix by bumping the pinned digest;
  that is the point of the gate.
