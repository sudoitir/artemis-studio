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
Studio's tests do not exercise the failure modes that matter in production: a live broker failing over to its backup, a network partition, a database restart. Accessibility has not been audited against a standard, and there is no runbook for operators. These gaps matter most to teams running Studio on real brokers, and they are cheap to close before a stable release.

## What Changes
- Integration tests against Artemis HA pairs (live and backup failover) and clusters
- Chaos and failover tests with the behaviour Studio is expected to show
- A WCAG 2.2 AA audit with automated axe checks in CI, and the fixes
- An operations runbook: install, upgrade, backup, incident playbooks

## Capabilities
### New Capabilities
- `quality-gates`: Failover and chaos test suites, accessibility gates and the operations runbook.
### Modified Capabilities
- `operational-health`: Expected behaviour under broker, network and database failure is stated and tested.

## Out of scope
- Formal certification
- Load testing (change 19)
- Mobile viewports

## Depends on
- 11-high-availability

## Execution
**Subagent-driven, plus a reviewer before the PR**: the HA, chaos, accessibility and runbook parts are independent tasks with their own finish lines. None is adversarial, so a workflow is not worth its cost.

## Impact
Test suites, CI, admin UI accessibility, operational health, documentation.
