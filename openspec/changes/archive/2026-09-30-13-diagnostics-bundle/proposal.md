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
When something goes wrong, users describe it from memory, and maintainers ask for the version, the environment, logs and settings one question at a time. Users are rightly careful about what leaves their installation. Nothing today collects diagnostics safely, and airgapped installations have no way at all.

## What Changes
- A "Report a bug" action pre-fills a GitHub issue with version, environment and sanitized configuration; nothing leaves the browser without the user clicking.
- A support bundle download: logs, settings, thread dump, health, plugin list and versions, redacted by the redaction rules of change 03.
- A preview shows exactly what is included and allows excluding sections.
- Works offline.
- Admin-only and audited.

## Capabilities
### New Capabilities
- `diagnostics`: bug report pre-fill and redacted support bundle
### Modified Capabilities
- none

## Out of scope
- Automatic or background telemetry.
- Uploading bundles to any service.
- Remote access for support.

## Depends on
03-secrets-hardening (redaction rules)

## Execution
**Inline**: one small, well-bounded feature in one context.

## Impact
Admin UI, API, logging, audit, documentation.
