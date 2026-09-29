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
Retention exists per store today: metric partitions, message index partitions, event and request-reply reapers and capture limits. Audit has no retention at all, and there is no single place where an operator sees or sets any of it. Operators cannot tell how much space each store uses, what a new policy would delete, or when a store is about to grow out of bounds. Plugins that keep their own data have no way to join a common lifecycle.

## What Changes
- One retention settings page for every store: metrics, broker events, request-reply flows, captured payloads, message index, audit, and the other stores that grow with use (bulk runs, transfer staging, notification deliveries, alert history, expired sessions); with defaults, bounds and a dry-run preview of what a new policy would purge.
- Audit retention: default keep forever, configurable.
- Size and row quotas per store with warning thresholds.
- A storage health page: table sizes, growth, bloat and dead tuples, last vacuum, partition coverage; with alerts.
- A public plugin contract so plugins register their stores, retention rules, dry-run and metrics.
- Purges run once per installation (through the existing installation-wide job scope, ADR-0125), never block normal writes, and are audited.

## Capabilities
### New Capabilities
- `data-lifecycle`: retention, quotas and storage health for every store, including metrics, and the housekeeping contract for plugins

### Modified Capabilities
- `audit-log`: audit retention and purge auditing

## Out of scope
- Changing what data each store records.
- Archiving purged data to external storage.
- Legal hold (a plugin may add it later through the SPI).

## Depends on
none

## Execution
**Subagent-driven**: separable pieces (settings UI, storage health, purge engine, plugin SPI) can be built by focused subagents against settled specs.

## Impact
Persistence layer, scheduled jobs, settings, admin UI, plugin API (`@PluginApi`), audit.
