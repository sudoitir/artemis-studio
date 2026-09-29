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
API tokens already have scopes, expiry, revocation and last-used tracking (ADR-0039). They cannot be rotated without downtime, admins cannot cap their lifetime, and nothing limits how hard one token can hit Studio. Agents connecting over MCP hold the same token, but cannot be restricted to particular tools or to read-only, and their actions are not summarised per token. The command line client (14), MCP and automation all lean on tokens, so these gaps matter now.

## What Changes
- Token rotation: a new secret with an overlap window during which both work.
- An administrator-set maximum token lifetime.
- Per-token rate and concurrency limits answered with 429 and standard headers; per-user request limits.
- A per-token usage summary in the audit trail.
- MCP: a token can be restricted to named MCP tools; a global and a per-token read-only mode.
- Every agent action is audited with the token and the tool name.

## Capabilities
### New Capabilities
- none
### Modified Capabilities
- `api-tokens`: rotation, lifetime policy, rate limits and usage summary
- `mcp-server`: tool allow-lists, read-only mode and per-action audit

## Out of scope
- OAuth flows for tokens.
- Billing or quotas across users.
- Changing the token format.

## Depends on
none

## Execution
**Inline**: extends an existing, well-bounded capability in one place.

## Impact
Security layer, API, MCP surface, admin and account UI, audit.
