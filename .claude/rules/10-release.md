# Rule: versioning, changelog, releases

See [ADR-0042](../../docs/adr/0042-calver-releases-on-docker-hub.md) for why.

## CalVer — `YYYY.MM.PATCH`

- `2026.09.0`, `2026.09.1`, … `2026.10.0`. `PATCH` is a counter that resets to `0`
  the first release of each calendar month (UTC), not a day-of-month.
- The version is **derived from git tags by CI** (`date +%Y.%m` + one past the
  highest existing `PATCH` for that month). It is never written into `pom.xml` —
  that stays `0.1.0-SNAPSHOT` in git and is set only in the CI working tree.
- Do not tag manually and do not add a version-bump commit.

## Every push to `main` is a release

The `release` job in `.github/workflows/ci.yml` does all of it, with no manual step:

- pushes the image to Docker Hub — `sudoit1/artemis-studio`, `linux/amd64` +
  `linux/arm64`, tags `:<version>` (immutable), `:<YYYY.MM>` (moving month pointer),
  `:dev` (moving channel pointer);
- promotes the changelog `## [Unreleased]` section to `## [<version>] — <date>`;
- creates an annotated git tag on the release commit;
- creates a GitHub Release with the promoted changelog section as the body and the
  `artemis-studio-<version>.jar` + its `.sha256` attached.

## Dev channel (pre-stable)

There is no stable release yet. Until there is:

- **no `:latest` tag** is published — `:dev` is the moving pointer;
- GitHub Releases are created with `prerelease: true`.

### Going stable

When the project cuts its first stable release, three edits flip the channel:

1. `ci.yml` — add `:latest` to the `build-push-action` tag list.
2. `ci.yml` — drop `prerelease: true` from the `action-gh-release` step.
3. `deploy/compose/compose.prod.yaml` + `deploy/compose/.env.example` — change the
   `STUDIO_IMAGE` default from `:dev` to `:latest`.

## Changelog is a merge-time obligation

`CHANGELOG.md`, [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

- Any PR that changes user-visible behaviour adds a bullet under `## [Unreleased]`,
  under one of `Added` / `Changed` / `Deprecated` / `Removed` / `Fixed` / `Security`.
- Write for someone **upgrading**, not for someone reading the diff.
- Internal refactors, test-only changes, and CI tweaks get no entry.
- Never hand-edit a released (`## [YYYY.MM.N]`) section — it is a historical record.

### Breaking changes

CalVer carries no compatibility signal, so a breaking change must announce itself: a
`### Breaking` block at the **top** of that version's section, stating what broke and
the exact migration step.
