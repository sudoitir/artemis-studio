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
A runtime plugin cannot add a way to sign in. Identity providers are a kernel contract (ADR-0073) that only built-in modules can contribute, and the published plugin API has no part of it, so a plugin author who wants directory or other credential sign-in must fork Studio. Plugins also cannot read the metric history Studio keeps: they can publish metrics and alert rules can watch them, but a plugin that wants to analyse a queue's depth or a broker's memory over time has no way to read it. Both are generic gaps in the extension contract.

## What Changes
- The plugin API lets a plugin contribute a credential sign-in provider. Studio keeps the one login path: throttling, lockout, MFA, session issue, audit and group-to-role mapping stay Studio's, and the plugin only answers whether the credentials match and who the user is.
- A plugin's provider appears on the login screen and in the provider list while the plugin is active, and disappears when it is not.
- A plugin can re-check whether an external identity is still valid, so revoked users lose their sessions without waiting for their next sign-in.
- The plugin API lets a plugin read metric history for the clusters and resources its acting user may read, within the existing query bounds.
- These are new `@PluginApi` surfaces: `Contract.VERSION` is bumped if japicmp flags a break.

## Capabilities
### New Capabilities
- none
### Modified Capabilities
- `identity-and-sessions`: plugins can contribute credential sign-in providers through the one login path
- `plugin-metrics`: plugins can read metric history under the acting user's permissions

## Out of scope
- Redirect sign-in (OIDC-like) providers from plugins.
- Plugins changing throttling, MFA or session rules.
- Writing metric history from a plugin beyond publishing its own metrics.

## Depends on
- 04-local-account-hardening (the login path a plugin provider joins has its lockout and MFA)
- 07-plugin-signing-and-trust (a plugin that can sign users in must be verified)

## Execution
**Inline, plus a reviewer before the PR**: two narrow additions to the plugin contract in one context, and a sign-in extension point is security-critical.

## Impact
Plugin API (`@PluginApi`), plugin runtime, security layer (login path and provider list), metrics read model, SDK documentation.
