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
Operators building roles report that permissions contributed by plugins are missing from the role editor's permission list, so a plugin's features cannot be granted through the UI. The cause is not known yet. Today nothing checks that the permissions a plugin declares, or that code guards with `@PreAuthorize`, are the ones actually registered, so a mismatch stays silent. The role editor is also a flat list: with many plugins it is hard to search, gives no descriptions and cannot show what a user will end up able to do.

## What Changes
- Find the root cause of missing plugin permissions in the role editor and fix it.
- Every registered permission (core and every active plugin) appears in the role editor; a plugin's permissions disappear when the plugin is removed.
- A startup consistency check compares declared permissions (plugin manifests, method guards) with registered ones and reports mismatches in operational health.
- Redesign the permission picker: grouped by module or plugin, searchable, with descriptions, scope chips (global, environment, cluster) and bulk select per group.
- Show an effective-permissions preview for a chosen user, and a diff between two roles.

## Capabilities
### New Capabilities
- none
### Modified Capabilities
- `authorization`: a complete, live permission catalog and a consistency check
- `operator-ui`: a grouped, searchable permission picker with preview and role diff
- `operational-health`: reports permission mismatches

## Out of scope
- Changing how permissions are granted or scoped.
- New permissions for existing features.
- Editing built-in roles (they stay fixed).

## Depends on
none

## Execution
**Inline**: one root-cause investigation plus one focused UI redesign fit a single context.

## Impact
Security layer (permission registry), plugin runtime, admin UI (role editor), operational health.
