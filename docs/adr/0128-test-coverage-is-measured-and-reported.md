# ADR-0128: Test coverage is measured on every pull request and reported, not gated

- **Status**: accepted
- **Date**: 2026-09-29
- **Deciders**: Mahdi Amirabdollahi

## Context

Studio has about 1,300 backend tests and a Vitest suite for the UI, and nothing says what they
exercise. A reviewer cannot tell whether a change is tested except by reading the tests, and a
module that quietly lost its tests does not show up anywhere.

## Decision

**We will measure line and branch coverage for the backend and the UI on every pull request that
runs their tests, and report it in the job summary and as a downloadable HTML report, with no
threshold that fails a build.**

- Backend: JaCoCo 0.8.15's agent records every Surefire run into `target/jacoco.exec`, and
  `verify` writes the report to `target/site/jacoco`. In CI each of the three shards uploads its
  exec file, and a `coverage` job merges them with the JaCoCo CLI (resolved through Maven) into one
  report. The job is part of `ci-ok`, so broken coverage wiring is caught.
- UI: Vitest's V8 provider (`@vitest/coverage-v8`, pinned to Vitest's version) through
  `npm run test:coverage`, over `src/`, excluding tests, the test harness and generated types.
- Both summaries go to the job summary; both HTML reports are artifacts kept for 14 days.
- No external coverage service. The numbers stay in GitHub, with no token or third-party app.

## Consequences

- Every PR shows what its tests cover, and a drop is visible in review.
- The agent and V8 add a few percent to test time.
- Without a threshold, coverage can fall without failing anything. Once the numbers have settled,
  a floor per side is a one-line change (`coverage.thresholds` in Vitest, the JaCoCo `check` goal).

## Alternatives considered

- **A hard threshold now.** Rejected until a baseline exists: a number picked before measuring
  either fails at once or is too low to matter.
- **Codecov or Coveralls.** Rejected for now: a third-party service and token for what the job
  summary already shows. PR comments and trend graphs are what it would add.
- **Coverage per shard only.** Rejected: three partial reports that no one can add up.
