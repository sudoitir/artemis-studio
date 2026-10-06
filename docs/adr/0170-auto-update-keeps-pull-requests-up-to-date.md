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

1. **A workflow updates the waiting pull requests.** `pr-auto-update.yml` runs on every push to
   `main` (and by hand). It lists the open pull requests against `main` and, for each one that
   has auto-merge enabled, is not a draft, is not from a fork and lacks commits of `main`, merges
   `main` into its branch with `PUT /repos/{owner}/{repo}/pulls/{n}/update-branch`. CI re-runs on
   the updated branch, and auto-merge merges it when `ci-ok` is green, so what `main` receives is
   exactly the tree CI verified.
2. **A merge update, not a rebase.** The endpoint merges; a rebase would rewrite authors' branches.
   The request carries `expected_head_sha`, so a push to the branch after the listing makes the
   call fail instead of merging into a branch nobody saw.
3. **A fine-grained personal access token pushes the update.** A push made with `GITHUB_TOKEN`
   starts no workflow, so CI would not re-run and auto-merge would wait forever. The
   `PR_UPDATE_TOKEN` secret holds a token scoped to this repository only, with Contents and Pull
   requests read and write. Without the secret the workflow warns and exits successfully, so
   it does nothing until the token exists.
4. **Pull requests are marked for merging with `gh pr merge <n> --merge --auto`.** Only those are
   updated, so a pull request someone is still working on, or has not marked ready, is left alone.
5. **The merge queue is removed.** `ci.yml` loses its `merge_group` trigger and the
   `github.event_name != 'push'` conditions return to `== 'pull_request'`.

## Consequences

- Merging one pull request no longer needs a hand update of the others; they update, re-run CI
  and merge on their own.
- Each push to `main` re-runs CI on every waiting pull request, one run per update. That is the
  cost ADR-0126's rule always had, now paid without anyone's time. A burst of merges may
  update a pull request more than once; a newer run of the workflow cancels the one in progress.
- Pull requests update one after another rather than being tested together as a queue does, so a
  pull request can be updated again before its CI finishes. It converges, and the last one in is
  never merged untested.
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
