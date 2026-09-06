# Rule: commit messages are the changelog

See [ADR-0051](../../docs/adr/0051-changelog-generated-from-commits.md) for why.

There is no `CHANGELOG.md` and no `## [Unreleased]` section to edit. When CI cuts
a release it runs `git-cliff` over the commits since the previous tag and writes
`changelog/<version>.md`. **The commit message is the release note**, so write it
for the person upgrading, not only for the person reading the diff.

Preview what the next release will say at any time:

```bash
just changelog
```

## Conventional Commits

Subject line: `type(scope)!: description`, imperative mood, no trailing period.
[Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/).

| Type | Lands under | Use it for |
| --- | --- | --- |
| `feat` | Added | A capability an operator did not have before |
| `fix` | Fixed | Behaviour that was wrong and now is not |
| `perf`, `revert` | Changed | Same capability, different behaviour or cost |
| `deprecate` | Deprecated | Still works, will not forever — say what replaces it |
| `remove` | Removed | Gone. Say what to use instead |
| `security` | Security | A vulnerability, an exposure, a tightened default |
| `docs`, `test`, `chore`, `ci`, `build`, `style`, `refactor` | *nothing* | Internal work an upgrader cannot observe |

The scope is optional and names the area, not the file: `feat(rr):`,
`fix(topology):`, `security(auth):`.

A subject that matches no type is still recorded, under **Changed**, so nothing a
release contains can silently vanish. That is a safety net, not the target.

## The body is the entry

Everything after the blank line is published verbatim under the subject, so:

- **Write for someone upgrading.** What changed for them, what they must do, what
  they will notice. Rationale is welcome — this project's commits carry it well —
  but it goes after the part an operator needs.
- **State the migration step** for anything that breaks, in the body, in full.
- Trailers (`Co-Authored-By`, `Claude-Session`, `Signed-off-by`, `Refs`, `Closes`)
  are stripped before publication. Everything else is not.
- A commit whose type gets no entry needs no body discipline; write for the diff.

## Breaking changes

Mark them, or they read as ordinary features: either a `!` before the colon
(`feat(rr)!: replace replyAddress with replyAddresses`) or a `BREAKING CHANGE:`
paragraph in the body. Marked commits are collected into a `### Breaking` block at
the **top** of the version's section, above everything else, and a skip rule can
never drop one. CalVer carries no compatibility signal, so this marking is the
only warning an upgrader gets.

## `changelog/unreleased.md` — the exception

Optional, usually absent. It holds a note **no single commit can carry**: a
migration step several commits add up to, or a warning about the release as a
whole. CI splices it above the generated entries and deletes it.

Reach for it rarely. A note in a commit body cannot drift from the change it
describes; a note in this file can.

## What is in `changelog/`

One file per released version, plus `README.md` — an index rewritten from the
files beside it by `scripts/changelog-index.py`, never by hand. A released file is
a historical record: never edit one.
