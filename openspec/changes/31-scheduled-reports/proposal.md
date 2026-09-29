## How to run this change
This change states **requirements only**. Run it in a fresh Claude session, in number order:

1. `git pull --ff-only` on `main`; branch for this change.
2. Read this proposal, its specs, the capabilities it names in `openspec/specs/`, and the ADRs they cite.
3. Brainstorm and investigate (`/opsx:explore`, `superpowers:brainstorming`); check libraries with ctx7. Ask the user only what is really theirs to decide.
4. `/opsx:update`: add `design.md`, sharpen the specs (turn ADDED into MODIFIED where a requirement changes an existing one), replace the stub `tasks.md`.
5. `/opsx:apply` with the harness under **Execution**. New decisions get an ADR.
6. Verify: `just verify`; for UI, run Studio and check screenshots (light and dark, empty and error states).
7. PR, merge on green CI, `/opsx:archive`.

## Why
People who do not open Studio still need to see queue depth, alerts, SLOs and audit summaries. Today an operator exports data by hand. Studio already delivers notifications through channels, so it can deliver reports the same way on a schedule.

## What Changes
- Scheduled CSV and JSON reports: queue depth, alerts, SLOs, audit summaries
- Delivery to distribution lists through existing channels
- A report can be attached to an alert
- Delivery history with audit
- Each scheduled run happens once per installation

## Capabilities
### New Capabilities
- `scheduled-reports`: Scheduled, delivered reports with history.
### Modified Capabilities
- none

## Out of scope
- Report designers or custom queries
- PDF or chart rendering
- New channel kinds

## Depends on
- none (SLO reports need 22-slo-tracking once it exists)

## Execution
**Inline**: a scheduler, a few report generators and delivery over existing channels.

## Impact
Alerting channels, audit, scheduler, admin UI, database.
