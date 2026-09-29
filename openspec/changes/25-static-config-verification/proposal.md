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
Some `broker.xml` settings cannot be applied through the management API, such as global-max-size, the HA policy and acceptors. Studio can compare nodes and detect drift in what the API exposes, but an operator cannot state the expected value of a static setting or find out that a node runs something else. Teams that manage broker files through their own tooling feel this after an incident.

## What Changes
- Declare expected values for static settings the management API cannot apply
- Read the actual values from each node
- Report drift per node
- Drift feeds alerts
- The command-line tool can run the check and fail a CI job

## Capabilities
### New Capabilities
- none
### Modified Capabilities
- `broker-configuration`: Expected static settings and their verification.
- `broker-config-diff`: Static drift is reported per node.

## Out of scope
- Applying static settings to a broker (Studio only verifies)
- Editing broker files

## Depends on
- 14-cli (the CI check is run from the command line tool)

## Execution
**Inline**: extends existing declaration and drift concepts.

## Impact
Broker configuration declaration, drift detection, alerting, command-line tool contract, admin UI.
