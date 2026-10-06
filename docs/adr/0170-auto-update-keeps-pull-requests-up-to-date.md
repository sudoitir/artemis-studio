# ADR-0170: Auto-update keeps pull requests up to date

- **Status**: accepted; supersedes [ADR-0169](0169-a-merge-queue-tests-the-commit-it-merges.md)
  and keeps the rule of [ADR-0126](0126-pull-requests-verify-main-releases-what-changed.md)
- **Date**: 2026-10-06
- **Deciders**: Mahdi Amirabdollahi

## Context

[ADR-0126](0126-pull-requests-verify-main-releases-what-changed.md) makes the merge commit the
tree CI verified by requiring `ci-ok` on a branch that is up to date with `main`. With several
pull requests open, every merge makes the others out of date, and each waits until someone
updates it and CI re-runs. [ADR-0169](0169-a-merge-queue-tests-the-commit-it-merges.md) chose a
merge queue for that. GitHub offers merge queues only for repositories owned by an organisation,
and this one is owned by a personal account: enabling one returns HTTP 422. The queue was never
usable, and the `merge_group` trigger it added to `ci.yml` was dead weight.

## Decision

**We will keep the up-to-date rule and update the pull requests automatically.**

1. **A workflow updates the waiting pull requests one at a time, like a serial queue.**
   `pr-auto-update.yml` walks the open pull requests against `main` that have auto-merge
   enabled, are not drafts and are not from forks, oldest auto-merge first (`enabledAt`, then the
   PR number). Updating all of them at once would waste CI: only the first to merge counts, and
   the rest are behind `main` again. For each PR, in order:
   - Behind `main`: `main` is merged into its branch with
     `PUT /repos/{owner}/{repo}/pulls/{n}/update-branch`, whatever its last `ci-ok` says. A result
     on a head that is behind is about old code, and a cancelled run makes `ci-ok` fail too, so
     only the fresh run counts. It is now in flight and the walk stops.
   - Up to date with `main` and `ci-ok` failed: skipped and logged; its author has to fix it.
     Cancelled or stale on an up-to-date head: skipped with a warning to re-run its CI.
   - Up to date with `main` and `ci-ok` pending, running or green: it is **in flight**, the walk
     stops, and the PRs after it wait. Green means the merge is about to happen.
   - Conflicting with `main` (the update answers 422): skipped with a warning, and the walk goes on.

   CI re-runs on the updated branch and auto-merge merges it when `ci-ok` is green, so what `main`
   receives is exactly the tree CI verified.
   The walk runs on a push to `main` (the in-flight PR merged, so advance), when a `CI` run
   completes (the in-flight PR failed, so advance past it), every 30 minutes as a backstop, and by
   hand. `workflow_run` is used rather than `pull_request_target` so the token never meets an event
   a pull request controls. Runs never cancel each other (`cancel-in-progress: false`) so two walks
   cannot update at once; every run is a full walk, so a run that was replaced loses nothing.
2. **A merge update, not a rebase.** The endpoint merges; a rebase would rewrite authors' branches.
   The request carries `expected_head_sha`, so a push to the branch after the listing makes the
   call fail instead of merging into a branch nobody saw.
3. **A fine-grained personal access token pushes the update.** A push made with `GITHUB_TOKEN`
   starts no workflow, so CI would not re-run and auto-merge would wait forever. The
   `PR_UPDATE_TOKEN` secret holds a token scoped to this repository only, with Contents and Pull
   requests read and write, and Checks read to see `ci-ok`. Without the secret the workflow warns and exits successfully, so
   it does nothing until the token exists.
4. **Pull requests are marked for merging with `gh pr merge <n> --merge --auto`.** Only those are
   updated, so a pull request someone is still working on, or has not marked ready, is left alone.
5. **The merge queue is removed.** `ci.yml` loses its `merge_group` trigger and the
   `github.event_name != 'push'` conditions return to `== 'pull_request'`.

6. **Everything used is free on a public repository.** The workflow runs on the standard
   `ubuntu-latest` hosted runner, whose minutes are free for public repositories, and uses only the
   `gh` CLI, the REST API, a fine-grained personal access token and auto-merge, none of which is
   billed. It needs no larger runner, no Team or Enterprise feature and no paid marketplace app.

## Consequences

- Merging one pull request no longer needs a hand update of the others; they update, re-run CI
  and merge on their own.
- Merging one pull request no longer needs a hand update of the others; they update one at a
  time, re-run CI and merge on their own. CI runs on one updated branch at a time, which is the
  cost ADR-0126's rule always had, and none is spent on a branch that goes stale before it merges.
- The queue is serial, so the last of several pull requests waits for each CI run ahead of it
  (about fifteen minutes each), where a merge queue would test them together.
- A pull request that fails CI on an up-to-date head is skipped until its author pushes a fix, or
  until `main` moves and the walk reaches it again, which costs one CI run to confirm the failure.
- The schedule is a backstop, so a missed event delays the next update by at most 30 minutes.
- The token is a long-lived credential tied to its owner. It can push to every branch of the
  repository, so it expires, is limited to this repository, and is read only by this workflow,
  which never checks out or runs pull request code and passes pull request fields to the shell
  through `env`. The merge commits carry the token owner's name.
- A pull request that conflicts with `main` is not updated; the workflow warns and the author
  resolves it.

## Alternatives considered

- **A merge queue (ADR-0169).** Unavailable on a personal repository.
- **Move the repository to an organisation.** A larger change than the problem, for one feature.
- **Drop the up-to-date rule.** Two green pull requests could merge into a red `main`, and the
  no-re-test rule of ADR-0126 would no longer hold.
- **A GitHub App token instead of a personal access token.** It does not expire with a person and
  is not tied to one, but needs an app, a private key and a token-minting step. It can replace the
  token later without changing the workflow's shape.
- **`GITHUB_TOKEN`.** Its pushes do not trigger workflows.
