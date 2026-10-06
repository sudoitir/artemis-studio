# Rule: versioning, changelog, releases

See [ADR-0042](../../docs/adr/0042-calver-releases-on-docker-hub.md) for why.

## CalVer — `YYYY.MM.PATCH`

- `2026.09.0`, `2026.09.1`, … `2026.10.0`. `PATCH` is a counter that resets to `0`
  the first release of each calendar month (UTC), not a day-of-month.
- The version is **derived from git tags by CI** (`date +%Y.%m` + one past the
  highest existing `PATCH` for that month). It is never written into `pom.xml` —
  that stays `0.1.0-SNAPSHOT` in git and is set only in the CI working tree.
- Only tags matching `^[0-9]{4}\.[0-9]{2}\.[0-9]+$` are candidates when CI reads the
  highest `PATCH`, and the computed version is re-checked against that same pattern
  before anything is published. A tag of any other shape is ignored, not parsed.
- A version tag is **immutable on Docker Hub**, so it is never reused: CI refuses to
  release when the computed tag already exists on `origin`, and it tags the merge commit
  and pushes the tag *before* pushing the image. A failure after that burns the
  version number and the next push to `main` takes the following one. A gap in the
  sequence is expected and fine; a republished tag is not.
- Do not tag manually and do not add a version-bump commit.

## Every source push to `main` is a release

Pull requests carry the whole verification; `main` does not re-test ([ADR-0126](../../docs/adr/0126-pull-requests-verify-main-releases-what-changed.md)).
The ruleset requires the one `ci-ok` check on a branch that is up to date with `main`,
so the merge commit is the tree CI verified. A ready PR gets `gh pr merge <n> --merge --auto`; the
`pr-auto-update` workflow brings it up to date in its turn (one PR at a time, oldest auto-merge first), and it merges when CI is green
([ADR-0170](../../docs/adr/0170-auto-update-keeps-pull-requests-up-to-date.md)).

A push to `main` releases when it changes what the image is built from
(`src/`, `web/`, `clients/`, `pom.xml`, the Maven wrapper, `Dockerfile`, `.dockerignore`). A docs-,
site- or CI-only push releases nothing; its commits appear in the next release's
changelog. The path lists live in the `changes` job of `ci.yml`
([ADR-0088](../../docs/adr/0088-path-filtered-ci-and-releases.md)).

The `release` job in `.github/workflows/ci.yml` does all of it, with no manual step:

- renders the release notes from the commits since the previous tag, then tags the
  merge commit and pushes only the tag. It commits nothing, so `main` never moves on a
  release and no open pull request falls behind because of one
  ([ADR-0129](../../docs/adr/0129-releases-tag-the-merge-commit-and-commit-nothing.md));
- pushes the image to Docker Hub — `sudoit1/artemis-studio`, `linux/amd64` +
  `linux/arm64`, tags `:<version>` (immutable), `:<YYYY.MM>` (moving month pointer),
  `:dev` (moving channel pointer);
- attests the image and the jar keylessly with `actions/attest` (ADR-0139): SLSA provenance and
  a CycloneDX SBOM (Syft) for each, the image's pushed to Docker Hub as OCI referrers;
- creates a GitHub Release with the notes as its body and the
  `artemis-studio-<version>.jar`, its `.sha256`, its provenance bundle (`.jar.intoto.jsonl`) and
  both SBOMs (`.jar.cdx.json`, `.image.cdx.json`) attached, then verifies the attestations with
  the commands in `site/src/guide/verify-releases.md` and dispatches `pages.yml`
  so the site's changelog lists it (a release made with the workflow token triggers
  no workflow on its own).

After it, each registry gets the release only when its inputs changed since the newest
version already there, so a failed publish is retried by the next release:

- `publish-api`: the plugin API to Maven Central, when `src/main` or `pom.xml` changed, and
  attests the files it deployed;
- `publish-sdk`: `@artemis-studio/plugin-sdk` to npm, when `web/packages`, `web/src/sdk`,
  `web/src/kernel` or the web manifests changed. Its job stays in `ci.yml`: npm trusted
  publishing is bound to that file name;
- `publish-client-ts` and `publish-client-java`: `@artemis-studio/client` to npm and
  `io.github.sudoitir:artemis-studio-client` to Central, on **every** release, generated from the
  release's `web/openapi.json` (ADR-0151). The release also attaches `artemis-studio-<version>.openapi.json`.

A pull request that changes the image inputs scans the built image with Grype and fails on a
critical or high finding that has a fix; bump the base-image digest or the dependency.

`hub-description` pushes `docs/dockerhub.md` as the Docker Hub repository description
whenever that file changes, with or without a release. That file is the Hub's landing
page and is **not** `README.md`; edit it when the run instructions or the screenshots
change. `DOCKERHUB_TOKEN` must be a PAT with **read, write and delete** scope: the
description endpoint rejects a repo-scoped token with `Forbidden`.

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

## Changelog

Each release's notes are its GitHub release body, generated from the commit messages
in that release. `changelog/` keeps the files of the releases up to 2026.09.60 as the
historical record, and the site's changelog lists every release: it fetches the notes
of those without a file from GitHub Releases when it builds. There is no
`CHANGELOG.md` and no `## [Unreleased]` section.

- The obligation moved to the **commit message**: see
  [`05-commits.md`](05-commits.md) for the Conventional Commits format, which
  types produce an entry, and how a breaking change announces itself.
- `just changelog` renders what the next release will say.
- CI renders the notes with `git-cliff` (pinned in `cliff.toml` and
  `.github/workflows/ci.yml`) and splices `changelog/unreleased.md` when it changed
  since the previous release. Never hand-edit a released file or a release body — each
  is a historical record.

