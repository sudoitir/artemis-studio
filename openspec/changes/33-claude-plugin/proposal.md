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
Developers building on Artemis ask an AI assistant about addressing, routing, client configuration and troubleshooting, and the answers are often generic or wrong for Artemis. Studio already has an MCP server. A Claude Code plugin shipped in the Studio repository can give the assistant Artemis knowledge and a ready connection to Studio.

## What Changes
- A Claude Code plugin in the repository, released with Studio
- Skills for building Artemis-based applications: addressing, routing, client configuration, troubleshooting
- A connector to Studio's MCP server
- Installable from a marketplace entry
- Documented

## Capabilities
### New Capabilities
- `claude-plugin`: A Claude Code plugin with Artemis skills and an MCP connection.
### Modified Capabilities
- none

## Out of scope
- Plugins for other assistants
- Changes to the MCP server's tools (see change 05)
- Broker code generation

## Depends on
- none

## Execution
**Inline**: content and packaging in one directory, with no runtime code in Studio.

## Impact
A new top-level directory in the repository, release process, documentation.
