# ADR-0124: CodeQL and OSV-Scanner run in CI

- **Status**: accepted
- **Date**: 2026-09-28
- **Deciders**: Mahdi Amirabdollahi

## Context

CI built, formatted and tested Studio, and Dependabot raised version updates weekly. Nothing
checked for known vulnerabilities or insecure code patterns. When this was added, the first scan
found three critical CVEs in the Tomcat that Spring Boot 4.1 manages, plus advisories in two
development-only packages. They had shipped unnoticed.

## Decision

- **OSV-Scanner** (Google's reusable workflows, pinned to a release) scans `pom.xml`,
  `web/package-lock.json`, `site/package-lock.json` and the plugin template's `pom.xml`:
  - on pull requests, it fails when the PR introduces a vulnerability;
  - on pushes to `main` and every Monday, it fails on any vulnerability.
- **CodeQL** runs through the repository's code-scanning *default setup* (Java, TypeScript, Actions,
  Python) on pull requests and `main`. It is a repository setting, not a workflow file: GitHub
  refuses results from an advanced workflow while default setup is on.
- A finding is fixed: update the dependency, override a transitive version (a Maven property or
  npm `overrides`), or fix the code. A false positive is dismissed in GitHub with a written reason,
  never silenced in the workflow.

## Consequences

- A new CVE against a shipped dependency surfaces within a week, and a PR cannot add a known one.
- Some fixes run ahead of the Spring Boot BOM (`tomcat.version`). Each carries a comment saying
  when to drop it.
- CodeQL on a public repository is free, and both scans run in parallel with CI, adding no time
  to it. Keep default setup enabled; turning it off would leave static analysis without a runner.

## Alternatives considered

- **OWASP dependency-check.** It needs an NVD API key and a slow database sync, and
  misidentifies shaded jars.
- **Dependabot security alerts only.** They are a repository setting and not visible in a PR
  check, and they do not cover static analysis.
