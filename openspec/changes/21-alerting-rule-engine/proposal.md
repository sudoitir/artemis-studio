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
Alerting today covers thresholds, cluster-state conditions, drift, a debounce through a pending state, and channels for webhook, Slack, email, Teams and PagerDuty. Operators cannot alert on a value changing fast or on data that stops arriving, cannot route an alert by cluster, environment or severity, and cannot silence, group or escalate. On-call teams feel the noise and the missed pages. This change makes the engine a complete on-call tool.

## What Changes
- Rate-of-change and absence conditions (no data, no consumption)
- Hysteresis, separate fire and resolve thresholds, so a rule does not flap (the for-duration already exists as the PENDING debounce)
- Routing trees that pick channels by labels such as cluster, environment and severity
- An Opsgenie channel kind
- Silences and maintenance windows
- Deduplication and grouping of related alerts
- Escalation policies with acknowledgement
- An alert history that can be searched beyond today's list

## Capabilities
### New Capabilities
- none
### Modified Capabilities
- `alerting`: Richer conditions, routing, silences, grouping, escalation and history.

## Out of scope
- SLO burn-rate alerting itself (change 22 uses this engine)
- On-call scheduling and rotation
- Two-way chat commands

## Depends on
- none

## Execution
**Subagent-driven**: conditions, routing, channel and escalation work are separable and each has a clear finish line.

## Impact
Alerting engine, notification channels, admin UI, database, audit.
