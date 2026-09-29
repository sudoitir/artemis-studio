# ADR-0129: A release tags the merge commit and commits nothing

- **Status**: accepted; supersedes the per-release changelog file of
  [ADR-0051](0051-changelog-generated-from-commits.md) and decision 5 of
  [ADR-0126](0126-pull-requests-verify-main-releases-what-changed.md)
- **Date**: 2026-09-29
- **Deciders**: Mahdi Amirabdollahi

## Context

A release wrote `changelog/<version>.md`, committed it to `main` with a `[skip ci]` release
commit, and pushed that commit with its tag. Under ADR-0126 the `main` ruleset requires every
pull request to be up to date with `main`. So each release commit put every open pull request
behind and forced a second full CI run before any of them could merge, for a commit that changed
nothing but a Markdown file. Pushing that commit also needed a deploy key as the ruleset's bypass
actor, and a retry loop for when another pull request merged while a release was building.

## Decision

**We will tag the merge commit that a release is built from, push only the tag, and keep each
release's notes in its GitHub release body. The release commits nothing to the repository.**

- The notes are rendered by git-cliff from the commits since the previous tag, as before, and are
  the release body. `changelog/unreleased.md` is spliced in when it changed since the previous
  release; it is never deleted by CI, because CI no longer commits.
- The files already in `changelog/` (every release up to 2026.09.60) stay as the historical
  record: three of those releases have no GitHub release, and two bodies differ from their files.
- The documentation site builds its changelog from both: the committed files, plus the body of
  every release that has no file, fetched from the GitHub API at build time. CI fails the site
  build rather than publish a changelog that stops at the committed files. The release dispatches
  the site build, because a release made with the workflow token triggers no workflow.
- The deploy key and its ruleset bypass are removed. Tags are outside the `main` ruleset, and the
  workflow token can push them.

## Consequences

- `main` moves only when a pull request merges. A release never puts an open pull request behind.
- A release cannot race another merge: it pushes nothing to `main`, so the retry loop is gone.
- No credential can bypass the `main` ruleset.
- Reading the release notes offline, from a clone, now needs the tag and `just changelog`-style
  rendering, or GitHub Releases. The site and GitHub Releases are the places to read them.
- The site build depends on the GitHub API. A local build without network shows only the
  committed notes and says so.

## Alternatives considered

- **Keep committing the changelog file.** Rejected: a second CI run for every open pull request
  after every release, and a bypass credential on `main`.
- **Drop the up-to-date rule.** Rejected: the merge commit would no longer be the tree CI verified,
  which is what lets `main` skip the re-test (ADR-0126).
- **Commit the notes to a separate branch.** Rejected: a second place to keep in step, for text
  GitHub Releases already holds.
- **Regenerate every release's notes from tags.** Rejected: the committed history is not
  reproducible from tags (hand-converted early notes, `unreleased.md` splices).
