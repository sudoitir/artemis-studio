---
name: openspec-git-discipline
description: Use when running OpenSpec propose, apply, verify or archive, where branches, commits, PRs and merges shape git history.
license: MIT
compatibility: Requires git, gh and OpenSpec workflow artifacts.
---

# OpenSpec Git Discipline

One change, one branch, one PR. Propose runs straight into apply and on to the merge, with no
pause for review. Committing, pushing, opening the PR and merging are part of the flow and
need no separate approval. Stop only for a blocker or a question only the user can answer.

## Flow

| Step | Do |
| --- | --- |
| Start | `git switch main && git pull --ff-only`, then `git switch -c <type>/<change>`. |
| Propose | Write the artifacts, commit `docs(openspec): propose <change>`, go on to apply. |
| Apply | Implement every task. Commit each increment that passes its check, with a message that is its release note (`05-commits.md`). |
| Verify | `just verify` passes, and every task in `tasks.md` is checked. |
| Archive | `/opsx:archive` on the same branch, commit `docs(openspec): archive <change>`, so specs and code land together. |
| PR | Rebase on a fresh `main`, push, `gh pr create`. |
| Merge | Once every CI check is green, `gh pr merge --merge --auto` adds the PR to the merge queue (ADR-0169), which re-runs CI on the exact commit it merges and deletes the branch. A red check is fixed on the branch. |
| Finish | `git switch main && git pull --ff-only`, delete the local branch. |

## Red Flags

- Stopping after propose to wait for review.
- Pushing to `main` directly.
- Archiving with unchecked tasks or a failing `just verify`.
- Merging past a red CI check.
