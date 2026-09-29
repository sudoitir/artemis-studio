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
Teams keep development, test and production clusters in step by hand. Studio can compare two nodes of one cluster and manage environments, but it cannot compare two environments or clusters, or move a configuration change from one to the other. Operators feel this at every release, where a missed queue or divert causes an incident.

## What Changes
- Compare queues, addresses, diverts, bridges and security settings across two environments or clusters
- Show a diff preview and promote selected differences with a dry run
- Roll back a promotion
- Export and import the configuration as YAML for GitOps
- A promotion passes through an approval gate, when one is installed
- Every promotion is audited

## Capabilities
### New Capabilities
- `environment-promotion`: Cross-environment comparison, promotion, rollback and GitOps transfer.
### Modified Capabilities
- `environments`: Environments can be compared and promoted between.

## Out of scope
- Promoting messages or data
- Automatic promotion on a schedule
- Implementing the approval gate itself

## Depends on
- 18-four-eyes-approvals (only through an approval gate, when one is installed; not required to run)

## Execution
**Subagent-driven**: comparison, promotion and transfer format are separable pieces with clear finish lines.

## Impact
Environments, broker configuration declaration, security layer, audit, admin UI, an export format.
