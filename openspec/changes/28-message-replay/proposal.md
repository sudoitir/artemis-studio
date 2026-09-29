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
Studio captures message payloads for diagnosis, but a captured message cannot be sent again. After an incident operators need to replay messages to a queue, one at a time or in bulk, sometimes after correcting a header or the body. Today they export payloads and write scripts. Replay is powerful and risky, so it needs previews, limits and a full audit trail.

## What Changes
- Replay captured payloads to a chosen target as a single message, a batch, or transformed
- Header and body edits before replay
- Dry run and rate limit
- Target validation: it exists, the user may send to it, size fits
- Sensitive values: captured content is stored masked with originals sealed, so a replay restores originals only for a user allowed to see them in clear, and never silently sends masked placeholders
- Interruption and provenance handled as cross-broker transfers already are
- Every replay is audited and linked in lineage

## Capabilities
### New Capabilities
- `message-replay`: Governed replay of captured messages, selected from the captured store.

### Modified Capabilities
- none

## Out of scope
- Replay of messages that were not captured
- Scheduled or automatic replay
- Cross-cluster replay beyond registered clusters

## Depends on
- 23-message-lineage

## Execution
**Inline**: one focused capability on top of capture and existing send operations.

## Impact
Message capture, message operations, lineage, audit, security layer, admin UI.
