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
Operators repeat the same filters, columns and sort orders on grids every day and lose them on reload. Teams cannot share a useful view, and there is no default that a cluster or environment can offer its users. Grids exist across Studio, each with its own controls, but nothing persists what an operator set up.

## What Changes
- Any grid's filters, columns and sort order can be saved as a named view
- A view is personal, shared with roles, or the default for a cluster or environment
- Views can be exported and imported as JSON
- A view never widens what its viewer may see

## Capabilities
### New Capabilities
- `saved-views`: Named, shareable, permission-respecting grid views.
### Modified Capabilities
- none

## Out of scope
- Saving dashboards or layouts beyond grids
- Cross-installation view sync

## Depends on
- 19-performance-hardening (it reworks the same grids for large lists first)

## Execution
**Inline**: one capability with a small data model and one UI pattern applied to the grids.

## Impact
Admin UI grids, a new persisted store, authorization checks, export format.
