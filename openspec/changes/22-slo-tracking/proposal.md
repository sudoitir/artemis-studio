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
Operators reason about reliability in terms of objectives, such as message age or consumption lag staying below a limit, but Studio only offers raw metrics and threshold alerts. There is no way to state a target over a window, see how much budget is left, or be warned when the budget burns too fast. Teams running business-critical queues need this to decide when to act.

## What Changes
- Define SLOs on queue depth, message age, consumption lag and request-reply latency
- Targets over rolling windows, with an error budget per SLO
- Multi-window burn-rate alerts delivered through the alerting engine
- An SLO dashboard showing objective, attainment and remaining budget

## Capabilities
### New Capabilities
- `slo-tracking`: Service level objectives, error budgets, burn-rate alerts and a dashboard.
### Modified Capabilities
- none

## Out of scope
- Business-level SLOs that need data Studio does not observe
- Reports (change 31 may deliver SLO summaries)

## Depends on
- 21-alerting-rule-engine

## Execution
**Inline**: one capability that reuses metrics history and the alerting engine.

## Impact
Metrics, alerting, request-reply tracing, admin UI, database.
