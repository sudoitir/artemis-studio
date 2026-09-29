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
Many Artemis installations run on Kubernetes, managed by the ArkMQ operator, but Studio has only container and compose deployments. Operators write their own manifests and register each broker by hand. A supported chart with safe defaults, and automatic discovery of operator-managed brokers, would make Studio a natural part of that platform.

## What Changes
- A Helm chart with hardened defaults
- Discovery and registration of brokers managed by the ArkMQ operator
- Custom resources for declarative cluster registration
- Documentation

## Capabilities
### New Capabilities
- `kubernetes-deployment`: Helm chart, operator discovery, declarative registration resources and docs.
### Modified Capabilities
- `cluster-registration`: Clusters can be registered declaratively and discovered.

## Out of scope
- Managing broker deployments (the operator does that)
- Other package formats
- Service meshes

## Depends on
- 11-high-availability (HA replica defaults)

## Execution
**Subagent-driven**: chart, discovery and the custom resource are separable pieces with clear finish lines.

## Impact
Deployment packaging, cluster registration, security defaults, release process, documentation.
