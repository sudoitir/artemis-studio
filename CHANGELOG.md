# Changelog

All notable changes to Artemis Studio are recorded here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning is CalVer
`YYYY.MM.PATCH` — see [`.claude/rules/10-release.md`](.claude/rules/10-release.md).

Images are published to Docker Hub as
[`sudoit1/artemis-studio`](https://hub.docker.com/r/sudoit1/artemis-studio).
There is no stable release yet: the moving tag is `:dev` and GitHub Releases are
marked as pre-releases.

## [Unreleased]

### Added

- Automated release pipeline. Every push to `main` builds a multi-arch
  (`amd64` + `arm64`) image, pushes it to Docker Hub as `:<version>`, `:<YYYY.MM>`
  and `:dev`, cuts a git tag, and creates a GitHub pre-release with the runnable
  jar and its SHA-256 checksum attached.
- `CHANGELOG.md` and `.claude/rules/10-release.md` documenting the CalVer scheme
  and the merge-time changelog obligation.

### Changed

- `just up` / `just down` now run the **prod** stack against the published image
  (previously the dev build). The dev stack — Postgres plus a real Artemis
  primary/backup pair, built locally — moved to `just dev-up` / `just dev-down`.
  `just prod-up` / `just prod-down` are removed.
- `just up` runs `just setup` first: it generates `deploy/compose/.env` with a
  random `SECRET_KEY` / `DB_PASSWORD` on first run and pins `STUDIO_IMAGE` to the
  newest published release tag (`:dev` until one exists), so a clean clone comes
  up with a single command.
- The published image is `sudoit1/artemis-studio` on Docker Hub. The previous
  `ghcr.io/sudoitir/artemis-studio` reference is dropped — CI only ever built that
  tag and threw it away.
