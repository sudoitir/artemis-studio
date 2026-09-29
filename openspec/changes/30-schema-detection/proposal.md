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
Messages carry JSON, XML, Avro or Protobuf, and nothing in Artemis says what shape a queue's messages take. Operators and developers find out by looking at samples. When a producer changes its format, consumers break and nobody sees it coming. Studio already samples payloads under data-governance rules, so it can infer structure and notice change.

## What Changes
- Infer message schemas for JSON, XML, Avro and Protobuf per address from samples
- A catalog of payload structures
- Schema drift detection with alerts
- Respect data-governance masking throughout

## Capabilities
### New Capabilities
- `schema-catalog`: Inferred schemas per address, a structure catalog and drift detection.
### Modified Capabilities
- none

## Out of scope
- Enforcing schemas at the broker
- A schema registry replacement
- Rejecting messages

## Depends on
- 21-alerting-rule-engine (schema drift becomes an alert condition of the reworked engine)

## Execution
**Subagent-driven**: each format is a separable inference job, and drift and the catalog build on them.

## Impact
Message capture and sampling, data governance, alerting, admin UI, database.
