## 1. Design
- [x] 1.1 Brainstorm and investigate; add design.md, sharpen specs and replace these tasks

## 2. Decision record
- [x] 2.1 ADR-0139: release attestations and the image scan; ADR-0042 and ADR-0102 point to it

## 3. Release pipeline
- [x] 3.1 `release` job: Syft CycloneDX SBOMs for image and jar; `actions/attest` provenance and SBOM attestations (image pushed as referrers); SBOMs and the jar bundle as release assets; verify step
- [x] 3.2 `publish-api`: attest the files deployed to Central
- [x] 3.3 `image` job: Grype report run and `--only-fixed --fail-on high` gate; the current image passes

## 4. Documentation
- [x] 4.1 `site/src/guide/verify-releases.md` and its sidebar entry
- [x] 4.2 `SECURITY.md`: CVE response policy
- [x] 4.3 `docs/dockerhub.md` link; `.claude/rules/10-release.md` lists the new release outputs

## 5. Finish
- [x] 5.1 `just verify` green; PR merged; release verified with the documented commands; change archived
