---
title: Verify a release
description: Check that an Artemis Studio image, jar or plugin kit package was built by this project's release workflow, and read its SBOM.
---

# Verify a release

Every release is built and published by one workflow,
[`ci.yml`](https://github.com/sudoitir/artemis-studio/blob/main/.github/workflows/ci.yml), and every
artifact it publishes carries a signed attestation of where it came from. There is no key of ours to
download: each signature is made with a short-lived certificate issued to that workflow through
[Sigstore](https://www.sigstore.dev/), so the signer is the workflow itself.

| Artifact | Signed | SLSA provenance | CycloneDX SBOM |
| --- | --- | --- | --- |
| Image `sudoit1/artemis-studio` | yes | yes | yes, attached to the image and the release |
| Jar on the GitHub release | yes | yes, also as `.intoto.jsonl` | yes, attached to the release |
| Plugin API on Maven Central | yes (and GPG) | yes | no |
| `@artemis-studio/plugin-sdk` on npm | yes | yes (npm provenance) | no |

The expected identity is always:

- repository `sudoitir/artemis-studio`
- signer workflow `sudoitir/artemis-studio/.github/workflows/ci.yml`

You need the [GitHub CLI](https://cli.github.com/) (`gh`), logged in to any GitHub account.

## The image

```bash
V=2026.10.0   # the release you run
gh attestation verify oci://docker.io/sudoit1/artemis-studio:$V \
  --repo sudoitir/artemis-studio \
  --signer-workflow sudoitir/artemis-studio/.github/workflows/ci.yml
```

A pass prints `✓ Verification succeeded!` and the attestation's source commit and workflow run. Add
`--bundle-from-oci` to read the attestation from Docker Hub instead of GitHub; it is stored next to
the image. For a pinned digest, use `oci://docker.io/sudoit1/artemis-studio@sha256:…`.

## The jar

Download `artemis-studio-<version>.jar` from the
[release](https://github.com/sudoitir/artemis-studio/releases), then:

```bash
gh attestation verify artemis-studio-$V.jar \
  --repo sudoitir/artemis-studio \
  --signer-workflow sudoitir/artemis-studio/.github/workflows/ci.yml
```

To check against the bundle on the release page instead of GitHub's attestation store, add
`--bundle artemis-studio-$V.jar.intoto.jsonl`.

## The SBOM

Each release page carries `artemis-studio-<version>.image.cdx.json` and
`artemis-studio-<version>.jar.cdx.json`. The image's SBOM is also a signed attestation on the image:

```bash
gh attestation verify oci://docker.io/sudoit1/artemis-studio:$V \
  --repo sudoitir/artemis-studio \
  --predicate-type https://cyclonedx.org/bom \
  --format json --jq '.[0].verificationResult.statement.predicate' > sbom.cdx.json
```

Feed it to your scanner, for example `grype sbom:sbom.cdx.json`.

## The plugin kit

The plugin API jar from Maven Central verifies like the release jar:

```bash
curl -fsSLO https://repo1.maven.org/maven2/io/github/sudoitir/artemis-studio/$V/artemis-studio-$V.jar
gh attestation verify artemis-studio-$V.jar \
  --repo sudoitir/artemis-studio \
  --signer-workflow sudoitir/artemis-studio/.github/workflows/ci.yml
```

For the npm package, in a project that depends on it:

```bash
npm audit signatures
```

It checks the registry signature and the provenance of `@artemis-studio/plugin-sdk`, and npmjs.com
shows the provenance (repository, commit and workflow) on the package page.

## When verification fails

`gh attestation verify` exits non-zero and says why. A file changed after the release has another
digest, so no attestation matches it. A package published anywhere but the release workflow has no
attestation from this repository. Do not run either, and
[report it](https://github.com/sudoitir/artemis-studio/security/advisories/new).
