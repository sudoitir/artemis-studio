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
Studio has no stated limits. Operators cannot tell how many clusters, nodes, queues or messages one Studio node can serve, and there is no repeatable way to find out. Slow SSE clients, large grids, chatty broker calls and heavy SQL queries are the likely failure points, but none is measured. Operators of large installations feel this first, when the UI stalls or memory grows. Today the SQL console has cost planning and bounded execution, and the event stream has replay and idle handling, but no published targets and no load test.

## What Changes
- Measured performance targets per Studio node, published in a sizing guide
- A repeatable load test kept in the repository
- Backpressure on the event stream so a slow client cannot grow memory
- Virtualized rendering wherever a list can be large
- Batched broker calls where several are made for one view
- Time, size and per-user concurrency bounds on the heavy endpoints that lack them (exports, bulk previews, flow and topology reads, audit and alert history), as the SQL console already has
- Measured results recorded in the sizing guide

## Capabilities
### New Capabilities
- `performance-sizing`: Published targets, a repeatable load test, the sizing guide and bounds on heavy endpoints.
### Modified Capabilities
- `realtime-stream`: Backpressure for slow subscribers.
- `operator-ui`: Large lists render without cost proportional to their length.

## Out of scope
- Horizontal scaling and multi-replica behaviour (change 11)
- Changing the polling model or the metrics store
- Mobile viewports

## Depends on
- 11-high-availability (targets are stated per replica, and both changes touch the event stream)
- 15c-design-system (it replaces the grids with one data table, which this change then hardens for large lists)

## Execution
**Subagent-driven, with the `planner` agent for the design**: the load test comes first, then the backend, database, event stream and UI fixes are independent tasks whose results feed one guide. Nothing here is adversarial, so a workflow is not worth its cost.

## Impact
Event stream, admin UI grids, heavy read endpoints, broker call paths, documentation, a load-test harness in the repo.
