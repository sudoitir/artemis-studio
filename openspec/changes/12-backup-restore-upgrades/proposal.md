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
Studio keeps its state in Postgres, including plugin schemas, but backup and restore are not documented or tested. Moving configuration between installations means re-creating clusters, roles and alert rules by hand. Migrations are not required to be safe across a rolling upgrade, and there is no upgrade or rollback guide.

## What Changes
- Documented and tested backup and restore of the Studio database including plugin schemas, with a restore test in CI.
- Export and import of Studio configuration (clusters, environments, roles, alert rules, settings) as a versioned document, with a dry-run import; secrets excluded or re-encrypted.
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
none

## Execution
**Inline**: documentation, one export format and migration discipline that fit a single context.

## Impact
Database migrations, settings, admin UI or API for export and import, CI, documentation.
