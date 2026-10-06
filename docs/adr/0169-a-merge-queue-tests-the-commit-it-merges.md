# ADR-0169: A merge queue tests the commit it merges

- **Status**: accepted; supersedes in part [ADR-0126](0126-pull-requests-verify-main-releases-what-changed.md)
  (the strict "up to date with `main`" rule)
- **Date**: 2026-10-06
- **Deciders**: Mahdi Amirabdollahi

## Context

[ADR-0126](0126-pull-requests-verify-main-releases-what-changed.md) made the merge commit the tree
CI verified by requiring `ci-ok` on a branch that is up to date with `main` (the ruleset's strict
status check policy). With several pull requests open, every merge made all the others out of date.
Each one had to be updated and re-tested by hand before it could merge, and with auto-merge on, a
PR sat waiting until someone did. The cost grew with the number of open PRs, which is what parallel
agent sessions and Dependabot produce.

## Decision

**We will merge through GitHub's merge queue, and drop the strict policy.**

1. **The queue replaces the strict check.** When a PR is ready, `gh pr merge <n> --merge --auto`
   adds it to the queue once `ci-ok` is green on the PR. The queue builds a temporary branch of
   `main` plus the PRs ahead of it plus this one, and merges only when CI is green on that exact
   commit. A PR no longer needs to be up to date with `main`.
2. **`ci.yml` runs the PR verification on `merge_group`** (`checks_requested`). Every
   verification job runs on any event but `push`, and `changes` takes its base from the merge
   group's event. `ci-ok` reports on the merge group, which is what the queue waits for. The
   `release`, `hub-description` and publish jobs stay on `push` to `main`, which the queue's
   merge still triggers, so a queued merge releases like any other.
3. **Sonar skips the merge group.** There is no PR to decorate, and the code was analysed on the
   PR (ADR-0140). The coverage job, which only feeds Sonar, skips it too. The analysis of the
   `main` push after the merge is unchanged.
4. **Ruleset settings:** merge method MERGE, grouping strategy ALLGREEN, up to 5 entries built and
   merged together, `strict_required_status_checks_policy` off, check timeout 60 minutes.

ADR-0126's guarantee holds: what `main` receives is the commit the merge group's `ci-ok`
verified, so a push to `main` still goes straight to `release` without a re-test.

## Consequences

- Merging one PR no longer sends the others back for an update and a re-run by hand.
- Each PR is verified twice, on its branch and in the queue, so a change costs about twice the
  runner minutes. A queue of several PRs shares one merge-group run when they group.
- A PR that conflicts with, or breaks against, the PRs ahead of it fails in the queue and drops
  out, instead of failing on `main`.
- Every new required check or `ci.yml` job must run on `merge_group` or `ci-ok` will not
  report for the queue. A job that must not run on the queue is conditioned on the event.
- The queue's merge may carry several PRs in one push, which is one release.

## Alternatives considered

- **Keep the strict policy and rebase by hand or by bot.** Cost grows with every open PR, and the
  rebases themselves re-run CI.
- **Drop the strict policy with no queue.** Two green PRs could merge into a red `main`, and the
  no-re-test rule of ADR-0126 would no longer hold.
- **Re-test on `main`.** Costs the minutes ADR-0126 removed, and finds the break after the merge.
