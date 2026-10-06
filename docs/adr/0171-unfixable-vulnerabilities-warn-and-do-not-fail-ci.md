# ADR-0171: Unfixable vulnerabilities warn and do not fail CI

- **Status**: accepted; amends [ADR-0124](0124-codeql-and-osv-scanner-run-in-ci.md)
- **Date**: 2026-10-06
- **Deciders**: Mahdi Amirabdollahi

## Context

[ADR-0124](0124-codeql-and-osv-scanner-run-in-ci.md) fails the `main` and weekly OSV-Scanner runs
on any vulnerability, and pull requests on any they introduce. That assumes every finding can be
fixed. `braces` 3.0.3, an npm development dependency, has advisory GHSA-vfj7-8cjw-p6xm with no
fixed version: its only range is `introduced 0` to `last_affected 3.0.3`. There is nothing to
upgrade to, so the weekly run failed with nothing anyone could do, and a red `main` that cannot be
fixed teaches people to ignore it.

## Decision

- **Everything is still reported.** The reusable OSV-Scanner workflows run with
  `fail-on-vuln: false` and still upload every finding to code scanning (the Security tab).
- **Only a finding with a fixed version fails.** A small gate, `.github/scripts/osv-gate.sh`,
  reads the scan JSON with `jq`. A finding is one vulnerability of one package version. It is
  fixable when a `fixed` event is in a range of an `affected` entry for that package in its
  ecosystem. Each fixable finding is an error naming package, version, ID and fixed version, and
  any of them fails the run.
- **The rest warn.** A finding without a fixed version is a `::warning::` annotation naming package,
  version and ID, and never fails the run.
- **Where it applies.** `main`, the weekly run and manual runs judge every finding (the `gate` job
  of `osv-scanner.yml` scans on its own, so no result size limit applies). A pull request judges
  only what it introduces, by comparing the base and head scans the reusable workflow uploads
  (`osv-gate` in `ci.yml`, part of `ci-ok`'s `needs`).
- A fixed version that appears later (a patched release, a new `fixed` event) turns the warning into
  a failure on the next weekly run, with no change to the repository.

## Consequences

- A vulnerability nobody can fix no longer blocks `main` or a PR, and stays visible in the Security
  tab and as a warning in the run.
- A fixable finding still fails exactly as before, so ADR-0124's rule to fix, override or dismiss it
  holds.
- The gate trusts the OSV record. A record with no `fixed` event but a fix elsewhere stays a warning
  until OSV is updated.
- The scan runs twice on `main` (once to report, once to judge); it takes seconds.

## Alternatives considered

- **Dismiss the alert in GitHub.** It silences the Security tab as well and does not stop the
  workflow from failing, which reads the scan and not the alert state.
- **An `osv-scanner.toml` ignore entry.** It hides the finding everywhere and has to be removed by
  hand when a fix ships.
- **`--all-vulns=false` or other scanner flags.** The scanner has no option to fail only on
  fixable findings.
