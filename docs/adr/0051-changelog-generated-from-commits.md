# ADR-0051: The changelog is generated from commit messages, one file per release

- **Status**: accepted
- **Date**: 2026-09-06
- **Deciders**: maintainer

## Context

[ADR-0042](0042-calver-releases-on-docker-hub.md) made every push to `main` a
release and gave the project a single `CHANGELOG.md` in Keep a Changelog format,
with a `## [Unreleased]` section that each user-visible change was expected to
add a bullet to before merging. CI promoted that section to a version heading and
used it as the GitHub release body.

Three problems showed up once the repository had real traffic through it.

**The file is a merge magnet.** Every change edits the same few lines at the top
of the same file. A `## [Unreleased]` section is the worst possible shape for
concurrent work: every branch appends to it, and every merge conflicts there.

**The entry drifts from the change.** The bullet is written in a different file
from the code, often at a different time, and reviewed as prose rather than
against the diff. Nothing fails when it is wrong, stale, or missing, so over time
some of it is all three.

**It was silently lost.** `CHANGELOG.md` reached `main` at zero bytes — the entire
release history of the project, deleted by a merge, with no test, no check and no
reviewer noticing. A single mutable file that everything edits and nothing
verifies is a fragile place to keep a historical record.

Meanwhile this project already writes long, careful commit bodies: motivation,
what was verified against the broker, what changed in the design and why. That
prose was being written once for the commit and then written again, worse and
shorter, for the changelog.

## Decision

We will **generate the changelog from the commit history** with
[git-cliff](https://git-cliff.org), and keep **one file per released version**
under `changelog/`.

- `CHANGELOG.md` is deleted. `changelog/README.md` is an index, rewritten from the
  files beside it by `scripts/changelog-index.py`. Released files are historical
  records and are never edited.
- Commits follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).
  The type selects the Keep a Changelog heading the entry lands under; `docs`,
  `test`, `chore`, `ci`, `build`, `style` and `refactor` produce no entry, which is
  the same editorial rule ADR-0042 stated in prose.
- **The commit body is published verbatim** under its subject. Provenance trailers
  are stripped; nothing else is. This is the point of the change: the note lives in
  the same commit as the code it describes and cannot drift from it.
- A commit whose subject matches no type is still recorded, under *Changed*.
  Silence is a worse failure than an untidy heading.
- Breaking changes are marked (`!` or a `BREAKING CHANGE:` paragraph) and collected
  into a `### Breaking` block at the top of the version's section. A skip rule can
  never drop one (`protect_breaking_commits`). CalVer carries no compatibility
  signal, so this marking is the only warning an upgrader gets.
- `changelog/unreleased.md` is an optional escape hatch for a note no single commit
  can carry — a migration step several commits add up to. CI splices it above the
  generated entries and deletes it.
- CI writes `changelog/<version>.md`, refreshes the index, commits both with the
  release tag, and uses the same text as the GitHub release body. `just changelog`
  renders the pending release locally, so the output is never a surprise.

The git-cliff version is pinned in both `cliff.toml`'s documentation and
`.github/workflows/ci.yml`: a published artifact should not change shape because a
tool released a minor version.

This supersedes ADR-0042's changelog mechanics only. Its versioning, tagging and
publishing decisions are unchanged.

## Consequences

- **The changelog cannot be forgotten, and cannot conflict.** There is no shared
  file to edit. Two branches merging cannot collide over release notes, and a
  merge cannot delete the history.
- **Commit discipline becomes user-facing.** A lazy subject line is now a lazy
  release note. This is the cost, and it is deliberate: the message is reviewed in
  the pull request alongside the change it describes, which is where it belongs.
- **Bodies are longer than a Keep a Changelog bullet.** A generated section reads
  more like release notes than an index. For a tool an operator upgrades in
  production that is the more useful artifact, but it is a change in voice.
- **The rendering is only as good as the history.** Rewriting or squashing commits
  after a release does not change an already-written file, which is correct, but it
  does mean the two can disagree. Released files stay authoritative.
- **A new external tool in the release path.** If git-cliff cannot be fetched, the
  release fails at changelog generation — before anything immutable is published,
  which is the safe side of the atomic tag-then-push ordering ADR-0042 established.
- Pre-ADR history is preserved: the sections from the old `CHANGELOG.md` were split
  into `changelog/<version>.md` files by hand, once, and are not regenerated.

## Alternatives considered

- **Keep `CHANGELOG.md`, add a check.** A CI job failing a PR with no `[Unreleased]`
  edit. Fixes forgetting, not conflicting or drifting, and rewards a bullet added to
  satisfy the check.
- **News fragments (towncrier, scriv, `changelog.d/`).** Solves the merge conflict
  properly, and is well established. But it is a *second* place to write the note,
  next to a commit body that already says it better — more manual work, not less.
- **release-please / semantic-release.** These derive the changelog from commits as
  we want, but they also own versioning, and they are SemVer-shaped. Version numbers
  here are CalVer derived from git tags (ADR-0042); handing that to a tool would
  either fight it or force SemVer on a product that deliberately does not claim
  compatibility from its version.
- **Changesets.** npm/monorepo-oriented, and fragment-based like towncrier. Wrong
  fit for a Maven-primary repository with one npm workspace inside it.
- **One file per release, written by hand.** Keeps the drift and the forgetting, and
  adds filing.
