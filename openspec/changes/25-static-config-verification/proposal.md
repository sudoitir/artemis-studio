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
Some `broker.xml` settings cannot be applied through the management API, such as global-max-size, the HA policy and acceptors. Studio can compare nodes and report drift between the declaration and what the API exposes, but importing `broker.xml` lists a static setting as unsupported and drops it from the declaration, so an operator cannot state its expected value or find out that a node runs something else. Teams that manage broker files through their own tooling feel this after an incident.

## What Changes
- Declare expected values for static settings the management API cannot apply
- Read the actual values from each node
- Report the difference per node through the existing drift report
- Drift feeds the existing configuration drift alert condition
- The command-line tool can run the check and fail a CI job

## Capabilities
### New Capabilities
- none

### Modified Capabilities
- `broker-configuration`: Expected static settings are declared as verify-only, read from each node and reported by the existing drift classification.
- `alerting`: The configuration drift condition covers static settings.

## Out of scope
- Applying static settings to a broker (Studio only verifies)
- Editing broker files

## Depends on
- 14-cli (the CI check is run from the command line tool)

## Execution
**Inline**: extends existing declaration and drift concepts.

## Impact
Broker configuration declaration and its XML import, drift detection, alerting, command-line tool contract, admin UI.
