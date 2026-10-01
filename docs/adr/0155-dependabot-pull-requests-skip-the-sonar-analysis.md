# ADR-0155: Dependabot pull requests skip the Sonar analysis

- **Status**: accepted
- **Date**: 2026-10-01
- **Deciders**: Mahdi Amirabdollahi

## Context

ADR-0140 analyses every pull request with SonarQube Cloud through the `SONAR_TOKEN` repository
secret, and exempts only fork pull requests, which get no secrets. GitHub gives a workflow run that
Dependabot triggers the Dependabot secret store instead of the Actions secrets, so the token is
empty there. The scanner then fails with "Not authorized or project not found", `ci-ok` turns red,
and every dependency bump is blocked by a failure that says nothing about the code.

A Dependabot pull request changes only manifests and lock files. Sonar has no new code to analyse
in it, and the analysis of the `main` push after the merge still runs with the token.

## Decision

**We will skip the `sonar` job on pull requests opened by Dependabot.** The job's condition checks the
pull request's author (`dependabot[bot]`), not the actor, so a maintainer re-running the checks of a
Dependabot pull request skips it too. `ci-ok` already treats a skipped job as passing. This amends
ADR-0140's "every pull request" to "every pull request except fork and Dependabot pull requests".

## Consequences

- Dependency bumps merge on the rest of CI: build, tests, OSV and the API checks.
- A fix commit a maintainer pushes onto a Dependabot branch is not analysed on the pull request.
  It is analysed on `main` after the merge, and any issue it raises is fixed in a follow-up change.
- No copy of the Sonar token lives in the Dependabot secret store, so one fewer place holds it.

## Alternatives considered

- **Store `SONAR_TOKEN` as a Dependabot secret.** Analyses a pull request that has no code to
  analyse, and keeps a second copy of the token to rotate.
- **Run the analysis from `pull_request_target`.** Runs with the base repository's secrets on code
  from the pull request's branch, which is the pattern GitHub warns against.
