# ADR-0202: CodeQL is removed; SonarQube Cloud is the static analysis

- **Status**: accepted; supersedes the CodeQL part of [ADR-0124](0124-codeql-and-osv-scanner-run-in-ci.md)
- **Date**: 2026-10-14
- **Deciders**: Mahdi Amirabdollahi

## Context

ADR-0124 turned on CodeQL's code-scanning default setup next to OSV-Scanner. SonarQube Cloud
(ADR-0140) has since become the static analysis every pull request must pass: its quality gate is part
of `ci-ok` and its issues are resolved before a merge. CodeQL then mostly duplicated it with a second
triage queue. Its findings were either Sonar's findings again or false positives that had to be dismissed
by hand, and a file move re-raised every dismissed alert as new, failing the check on code that did not
change.

## Decision

CodeQL is turned off (default setup set to not configured) and is not replaced by a workflow.
SonarQube Cloud is the one static analysis; OSV-Scanner stays for known vulnerabilities in
dependencies, as ADR-0124 and ADR-0171 describe.

## Consequences

- One triage queue for code findings: SonarQube Cloud's.
- OpenSSF Scorecard's SAST check no longer sees CodeQL and may score lower. Accepted.
- Past CodeQL alerts stay in the repository's history; nothing new is raised.

## Alternatives considered

- **Keep CodeQL and dismiss re-raised alerts after each move.** Recurring manual work for no new
  findings.
- **An advanced CodeQL workflow limited to fewer queries.** Still a second queue next to Sonar.
