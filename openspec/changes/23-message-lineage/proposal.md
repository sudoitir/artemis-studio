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
When a message goes missing or arrives late, operators reconstruct its path by hand across queues, diverts, bridges, other brokers and dead-letter queues. Studio can capture payloads and trace request-reply flows, but it cannot show one message's journey. Support engineers feel this most. The design must be honest: Studio observes only part of any path, and a gap must show as unknown rather than be guessed.

## What Changes
- Follow one message across queues, diverts, bridges, cross-broker transfers, DLQs and captured payloads
- Correlate by message ID, correlation ID and configurable headers
- A graph view of the path with timestamps and hops
- Bounded storage governed by the data lifecycle policy
- Unobserved hops are shown as unknown

## Capabilities
### New Capabilities
- `message-lineage`: Correlated message paths with an honest graph view, fed by captured messages, traced request-reply flows and transfer provenance.

### Modified Capabilities
- none

## Out of scope
- Guaranteeing complete lineage
- Tracing messages Studio never observes
- Replay (change 28 links to lineage)

## Depends on
- 02-data-lifecycle

## Execution
**Subagent-driven** with the `planner` agent for the design, since the correlation model is the hard part.

## Impact
Message capture, request-reply tracing, message index, database, admin UI, data lifecycle.
