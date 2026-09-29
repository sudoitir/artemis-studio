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
The SQL console reads and filters messages but stops there. Operators want to aggregate, such as counting messages per header or per minute, or to transform results, without exporting them. The console is deliberately read-only (ADRs 0058, 0063, 0064) and bounded, so any extension must keep both properties. Joins are risky because Artemis has no indexes across queues and a naive join could load a broker.

## What Changes
- Filter and transform processors over query results
- Aggregations: group by and windowed counts
- Joins only if a safe model over Artemis is proven; otherwise the rejection is documented
- A memory guard, next to the time and row bounds execution already has
- The existing plan operation covers processors and aggregations

## Capabilities
### New Capabilities
- none
### Modified Capabilities
- `sql-console`: Processors, aggregations, resource guards and explain plans, still read-only.

## Out of scope
- Any write, update or delete through SQL
- Persistent views or materialized results

## Depends on
- 19-performance-hardening (it sets the endpoint bounds and sizing this change must stay within)

## Execution
**Subagent-driven**: processors, aggregation and the join investigation are separable, and the join question needs its own finding.

## Impact
SQL console, query planner, governance masking, admin UI, documentation, an ADR for the join decision.
