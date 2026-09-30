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
API tokens already have scopes, expiry, revocation and last-used tracking (ADR-0039). They cannot be rotated without downtime, admins cannot cap their lifetime, and nothing limits how hard one token can hit Studio. Agents connecting over MCP hold the same token. A token can already be narrowed to read permissions, and mutations through it are audited with the token's name, but it cannot be restricted to particular tools, an installation cannot make the whole agent surface read-only, reads through MCP are not audited, and nothing summarises use per token. Administrators also cannot see or revoke another user's token when it leaks, because tokens are managed only from their owner's account page. The command line client (14), MCP and automation all lean on tokens, so these gaps matter now.

## What Changes
- Token rotation: a new secret with an overlap window during which both work.
- An administrator-set maximum token lifetime.
- Per-token rate and concurrency limits answered with 429 and standard headers; per-user request limits.
- A per-token usage summary in the audit trail.
- MCP: a token can be restricted to named MCP tools; an installation-wide read-only mode. Per-token read-only stays what it is today: a token granted only read permissions.
- Every agent action, reads included, is audited with the token and the tool name.
- Administrators see every token's metadata and can revoke any token; tokens unused for a set period are flagged. This changes the `identity-and-sessions` rule that keeps personal keys off the administration surface: owners still mint and manage their own keys only on the account page.

## Capabilities
### New Capabilities
- none
### Modified Capabilities
- `api-tokens`: rotation, lifetime policy, rate limits and usage summary
- `mcp-server`: tool allow-lists, read-only mode and per-action audit
- `identity-and-sessions`: the administration surface gains a metadata-only inventory of every user's keys with revoke

## Out of scope
- OAuth flows for tokens.
- Billing or quotas across users.
- Changing the token format.

## Depends on
01-permission-catalog (the token grant picker reuses the grouped permission picker)

## Execution
**Inline**: extends an existing, well-bounded capability in one place.

## Impact
Security layer, API, MCP surface, admin and account UI, audit.
