# Rule: how work happens here

## OpenSpec for every feature

No feature or behaviour change is implemented without an OpenSpec change. A change runs
from propose to merged PR in one go, with no pause for review (`openspec-git-discipline`).

1. `/opsx:propose` — create `openspec/changes/<name>/` with `proposal.md`,
   `tasks.md`, `design.md` (when it has design weight), and `specs/` deltas.
2. `/opsx:apply` — implement the checklist in `tasks.md`, straight after propose.
3. `/opsx:archive` — move the change to `openspec/changes/archive/` and merge its
   deltas into `openspec/specs/`.

`openspec/specs/` is the living source of truth. `openspec/changes/` holds in-flight
work and the queue of planned work. `/opsx:explore` is for no-stakes thinking first.

## Numbered changes

Planned work is queued in `openspec/changes/` as **numbered, requirements-only** changes,
`NN-<slug>`. The number is the run order. A change added between two existing ones takes a
letter suffix (`08b-<slug>`), so numbers never shift. A queued change states what and why,
not how. Each one runs in its own fresh session, following the "How to run this change"
block in its proposal: brainstorm, `/opsx:update` (design, sharpened specs, real tasks),
`/opsx:apply` with the harness its **Execution** line names, verify, merge, archive.
Unplanned work (a bug fix that grows into a feature, for example) gets a plain `<slug>` name.

## One git worktree per change

Changes run in parallel sessions, so each one works in its own git worktree and never in
the main checkout, which stays on `main` for whoever else is using it:

```bash
git fetch
git worktree add -b <branch> ../worktrees/artemis-studio-<NN-slug> origin/main
# … work, verify, PR, merge …
git worktree remove ../worktrees/artemis-studio-<NN-slug> && git branch -D <branch>
```

Two changes run in parallel only when neither depends on the other. Before the PR, rebase
onto the latest `main`. When two parallel changes both bump `Contract.VERSION` or add
Liquibase changesets, the second to merge rebases and renumbers its own. A session that
runs Studio for screenshots uses its own ports and compose project name, so parallel
sessions do not share a dev stack.

**Merging is automatic** (ADR-0170): once the PR is ready, `gh pr merge <n> --merge --auto`. The
`pr-auto-update` workflow keeps one PR with auto-merge on up to date at a time, oldest auto-merge
first; CI re-runs on it and it merges when green, then the next is updated. A PR that is up to date and whose `ci-ok`
failed is skipped until its author pushes a fix, so nobody updates a branch by hand. Wait for the merge with
`gh pr view <n> --json state,mergedAt`.

**A change is finished only when it is cleaned up:** the PR is merged, the worktree is
removed, the branch is deleted locally and on the remote, anything it started is stopped
(its compose stack, dev servers, test containers), and the main checkout is pulled with
`git pull --ff-only` when it is on `main` and clean. `git worktree list` no longer shows the
worktree.

Bug fixes and pure refactors do not need a proposal. Anything that changes what
the product does, does.

## ADRs for every significant decision

`docs/adr/`, English, Nygard style (`docs/adr/000-template.md`). Adding a
technology, changing a pattern, or departing from a recorded convention needs a
new sequential ADR before or with the change. Never edit an accepted ADR's
decision — supersede it and mark the old one superseded with a link.

OpenSpec proposals reference the ADRs they depend on.

## Library facts via ctx7, never memory

Any question about a library, framework, SDK, or CLI — including ones you think
you know — goes through the `ctx7` CLI:

```
npx ctx7@latest library "<name>" "<what to look up>"
npx ctx7@latest docs "<id>" "<what to look up>"
```

Max 3 calls per question. One concept per `docs` call. Training data on versions
and API signatures is stale; this project has already been bitten by it
(Testcontainers 2.x artifact rename, Boot 4 per-module auto-configuration).

## SonarQube Cloud before merge

Every change resolves its SonarQube Cloud issues before its PR merges (ADR-0140). CI's `sonar`
job fails `ci-ok` on a red quality gate, but the gate only looks at new code, so check the PR's
issues too once CI has analysed it: `search_sonar_issues_in_projects` with the PR's `pullRequest`
key (from `list_pull_requests`) and `issueStatuses` OPEN and CONFIRMED, and
`get_project_quality_gate_status`. The PR is done when that list is empty and the gate is green.

- Fix an issue in code whenever the rule is right.
- A genuine false positive is marked false positive in SonarQube Cloud with a comment saying why.
- A rule that conflicts with a recorded convention is scoped out in `pom.xml`
  (`sonar.issue.ignore.multicriteria`, rule and path) and its reason added to ADR-0140. Never a
  repo-wide exclusion without a reason, and never "accept" to make the list shorter.
