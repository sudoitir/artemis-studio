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
Everything in Studio is reachable through the UI and the HTTP API, but operators and CI want a command line: to script queue work, check configuration drift, or export configuration without writing HTTP calls. Users of automation today handle tokens, pagination and errors by hand. A single, cross-platform binary that speaks the published API removes that.

## What Changes
- A single binary for Linux, macOS and Windows on amd64 and arm64.
- Commands for clusters, queues, addresses, messages (browse, send, move, purge with `--dry-run`), SQL queries, tokens, configuration export and import, and a drift check.
- Output as table, JSON or YAML; stable exit codes.
- Shell completion; profiles or contexts.
- Non-interactive token authentication for CI.
- Built on the published API contract (09); released with Studio.

## Capabilities
### New Capabilities
- `cli`: the command line client for Studio
### Modified Capabilities
- none

## Out of scope
- Managing brokers directly (it talks to Studio only).
- A terminal UI.
- Plugin-specific commands.

## Depends on
09-public-api-contract; 05-api-tokens-and-mcp-scopes (tokens for CI)

## Execution
**Subagent-driven**: command groups, packaging and release automation are independent once the API contract is settled.

## Impact
New client artifact, release pipeline, documentation; no server change beyond what the API contract already provides.
