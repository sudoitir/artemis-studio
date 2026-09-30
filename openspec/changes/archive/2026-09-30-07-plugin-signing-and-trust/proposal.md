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
An uploaded plugin jar runs with the full trust of Studio. Installers are trusted and freshly authenticated, but nothing tells them who built a jar or whether it was changed. A stolen installer session or a swapped file yields arbitrary code in Studio. Plugin authors have no way to prove authorship, and the review screen does not show what identity stands behind a plugin.

## What Changes
- Plugin jars carry a signature.
- Studio verifies it against trusted publisher keys managed by administrators, before install and before update.
- Unsigned or untrusted plugins are refused unless an administrator explicitly allows them; the setting is off by default, the decision is audited, and the plugin shows an "unverified" badge.
- The review screen shows publisher, key fingerprint, requested permissions and, on update, the permission diff.
- The plugin template and SDK tooling can sign a plugin.
- **BREAKING**: unsigned plugins stop installing by default.

## Capabilities
### New Capabilities
- `plugin-trust`: publisher keys, signature verification, the unverified allowance, and how install, update and review use the trust decision

### Modified Capabilities
- none

## Out of scope
- A public plugin marketplace.
- Sandboxing plugin code.
- Revocation infrastructure beyond removing a trusted key (removing one does flag the installed plugins it signed).

## Depends on
06-supply-chain-security (plugin signing follows the release signing model and tooling)

## Execution
**Subagent-driven, plus a reviewer before the PR**: verification, the trust store, the review screen and the SDK signing tool are separable slices, and a gap here is arbitrary code in Studio.

## Impact
Plugin runtime, security layer, admin UI (plugin review and settings), plugin SDK and template, audit.
