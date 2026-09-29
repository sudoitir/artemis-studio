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
Studio exposes Prometheus metrics through Actuator, and nothing more. Operators who run OpenTelemetry cannot collect Studio's metrics, traces or logs, cannot follow a slow request through the broker call to the database, and cannot see Studio's own health in one place: job status, broker connection health and per-node request metrics exist, but pool use, poller lag and stream clients do not, and nothing puts them on one screen. There are no dashboards or alert rules to start from.

## What Changes
- OTLP export of metrics, traces and logs; configurable and off by default.
- Traces span an HTTP request, the management and core calls it makes, and the database.
- A Studio self-health view: scrape and poller lag, management call latency, database pool, event stream clients, job status.
- Grafana dashboards and Prometheus alert rules shipped in the repository and the release.
- Structured JSON logs carrying trace identifiers, for log shippers that do not speak OTLP.
- Documentation.

## Capabilities
### New Capabilities
- `telemetry-export`: OTLP export, cross-boundary traces and shipped dashboards and rules
### Modified Capabilities
- `operational-health`: the self-health view

## Out of scope
- Hosting a metrics backend.
- Application performance monitoring of brokers themselves.
- Log aggregation storage.

## Depends on
none

## Execution
**Subagent-driven**: OTLP export, self-health view and shipped dashboards and rules are separate slices.

## Impact
Application observability, operational health, release artifacts, documentation.
