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
Sessions live in the database and installation-wide jobs run once through ShedLock (ADR-0125), but the event stream hub is per instance. Two replicas behind a load balancer would show different events to different clients, keep separate caches, and both run pollers. Shutdown does not drain streams, and probes do not say what an orchestrator needs to know. There is no reference deployment to copy.

## What Changes
- Two or more stateless replicas behind a load balancer behave as one: stream events reach clients on every replica, caches are coherent, pollers and schedulers are single-leader or partitioned.
- Graceful shutdown drains event streams and in-flight operations.
- Readiness, liveness and startup probes with correct semantics.
- A compose HA template: two replicas, a load balancer and Postgres.
- A documented reference deployment.
- Failover tests.

## Capabilities
### New Capabilities
- none
### Modified Capabilities
- `realtime-stream`: events reach every replica's clients
- `operational-health`: probe semantics, graceful shutdown and leadership

## Out of scope
- Active-active across regions.
- Database high availability (Postgres is the operator's).
- Autoscaling policy.

## Depends on
none

## Execution
**Subagent-driven, plus a reviewer before the PR**: several independent slices, and concurrency across instances is risky enough to need a second look.

## Impact
Event stream, scheduling, caches, health probes, deployment templates, documentation, tests.
