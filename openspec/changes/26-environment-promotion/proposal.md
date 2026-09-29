## How to run this change
This change states **requirements only**. Run it in a fresh Claude session, in number order:

1. `git fetch`, then create a git worktree for this change on a new branch off `origin/main` (`.claude/rules/00-workflow.md`). Work only there, and remove it after the merge.
2. Read this proposal, its specs, the capabilities it names in `openspec/specs/`, and the ADRs they cite.
3. Brainstorm and investigate (`/opsx:explore`, `superpowers:brainstorming`); check libraries with ctx7. Ask the user only what is really theirs to decide.
4. `/opsx:update`: add `design.md`, sharpen the specs (turn ADDED into MODIFIED where a requirement changes an existing one), replace the stub `tasks.md`.
5. `/opsx:apply` with the harness under **Execution**. New decisions get an ADR.
6. Verify: `just verify`; for UI, run Studio and check screenshots (light and dark, empty and error states).
7. PR, merge on green CI, `/opsx:archive`.

## Why
Teams keep development, test and production clusters in step by hand. Studio can compare two nodes of one cluster, keeps a declaration per cluster with revisions, XML import and export and a canary-first apply, and groups clusters into environments. It cannot compare two clusters' declarations or two environments, or carry a change from one to the other, and there is no way to keep a whole environment's declarations in version control. Operators feel this at every release, where a missed queue or divert causes an incident.

## What Changes
- Compare queues, addresses, diverts, bridges and security settings across two environments or clusters
- Show a diff preview and promote selected differences: a promotion writes a new revision of the target's declaration, which is then applied through the existing canary-first apply
- Roll back a promotion by applying the target's previous revision, within what the apply may undo (it never destroys a queue or address)
- Export and import an environment's declarations as files for version control, in the declaration's existing XML interchange format; no second format
- A promotion passes through an approval gate, when one is installed
- Every promotion is audited

## Capabilities
### New Capabilities
- `environment-promotion`: Cross-environment comparison, promotion through the declaration, rollback and file-based transfer of an environment.

### Modified Capabilities
- none

## Out of scope
- Promoting messages or data
- Automatic promotion on a schedule
- Implementing the approval gate itself

## Depends on
- 18-four-eyes-approvals (only through an approval gate, when one is installed; not required to run)
- 25-static-config-verification (both change the cluster declaration; verify-only entries must exist before promotion copies them)

## Execution
**Subagent-driven**: comparison, promotion and transfer format are separable pieces with clear finish lines.

## Impact
Environments, broker configuration declaration and apply, security layer, audit, admin UI.
