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
Studio serves a springdoc-generated OpenAPI document under `/api/v1` and generates frontend types from it. That is enough for the UI but not a contract for other users. There is no versioning or deprecation policy, nothing detects a breaking change, pagination, errors and rate-limit headers vary by endpoint, retries of mutations are not safe, and no clients are published. The CLI (14) and automation need a stable, documented surface.

## What Changes
- The OpenAPI document is a published, versioned release artifact.
- A documented versioning and deprecation policy: deprecation headers and sunset dates; before stable, breaking changes are allowed but flagged.
- CI detects breaking changes against the last release.
- Standard pagination, problem+json errors and rate-limit headers across endpoints.
- Idempotency keys on mutating endpoints.
- Generated TypeScript and Java clients are published.
- Contract tests keep the API and the document in step.
- **BREAKING**: endpoints whose pagination, errors or headers differ are aligned to the standard.

## Capabilities
### New Capabilities
- `public-api`: the published, versioned, conventional HTTP API contract
### Modified Capabilities
- none

## Out of scope
- Changing what endpoints do.
- Clients for other languages.
- A GraphQL or gRPC surface.

## Depends on
none

## Execution
**Subagent-driven**: versioning policy, CI breaking-change checks, cross-cutting conventions and client generation are separable.

## Impact
HTTP API layer, release pipeline, generated clients, documentation, CI.
