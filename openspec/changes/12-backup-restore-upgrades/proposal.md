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
Studio keeps its state in Postgres, including plugin schemas, but backup and restore are not documented or tested. Moving configuration between installations means re-creating clusters, roles and alert rules by hand. Migrations are not required to be safe across a rolling upgrade, and there is no upgrade or rollback guide.

## What Changes
- Documented and tested backup and restore of the Studio database including plugin schemas, with a restore test in CI.
- Export and import of Studio configuration (clusters with their declarations, environments, roles and group mappings, alert rules, notification channels, settings) as a versioned document, with a dry-run import; secrets excluded or re-encrypted.
- Database migrations follow expand and contract so a rolling upgrade across replicas works.
- An upgrade and rollback guide.

## Capabilities
### New Capabilities
- `backup-and-config-transfer`: backup and restore, configuration export and import, safe upgrades
### Modified Capabilities
- `studio-settings`: settings take part in export and import

## Out of scope
- Continuous replication.
- Exporting metric history or audit.
- Automatic rollback of a failed upgrade.

## Depends on
- 03-secrets-hardening (secrets in an export are handled under the envelope scheme)
- 11-high-availability (rolling upgrades across replicas)

## Execution
**Subagent-driven**: backup and restore with its CI test, configuration export and import, and the migration discipline with its upgrade test are separable slices.

## Impact
Database migrations, settings, admin UI or API for export and import, CI, documentation.
