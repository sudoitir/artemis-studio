# ADR-0198: A Gatling load test publishes the sizing figures

- **Status**: accepted
- **Date**: 2026-10-13
- **Deciders**: Mahdi Amirabdollahi

## Context

Studio runs replicated on one PostgreSQL (ADR-0152), reads many brokers, streams to many browsers and runs
queries and exports of unbounded cost. Until now nobody could say how many clusters, queues or watchers one
deployment carries, or notice that a change made Studio slower. Operators sized by guessing, and a regression
showed up as a complaint.

## Decision

- **The load test is Gatling's Java DSL, in `load/`.** It is a standalone Maven project outside the main build
  and outside CI's verify: nothing in a pull request compiles or runs it. Gatling has native event-stream support;
  k6 would need a custom build for it. The test signs real accounts in over the API and drives UI reads (cluster
  views, queue pages, metric history), event-stream subscribers that read, plain sockets that stop reading,
  audit exports and SQL console queries. The heavy scenarios share one account so that its per-user limit
  (ADR-0196) is reached; a refusal with 429 is counted, not failed.
- **`just load` is the one command.** `scripts/load.sh` starts Studio in HA (the shipped `compose.ha.yaml`, two
  replicas, HAProxy, Postgres) with real Artemis brokers under its own compose project and port, seeds clusters,
  queues, messages, audit records and accounts through Studio's API and the brokers' own management, runs the
  simulation with broker traffic alongside, scrapes `/actuator/prometheus` before, during and after, writes
  `load/target/report/summary.{json,md}` and always removes the stack and its volumes. `just load-smoke` is a
  tiny profile that checks the test itself. A `workflow_dispatch` workflow runs the same and uploads the report; it
  never runs on a pull request.
- **Profiles are files** (`load/profiles/*.env`); any size is overridden from the environment. The `reference`
  profile is the one `docs/sizing.md` publishes.
- **Figures are published in `docs/sizing.md`** for a reference machine of 4 vCPU and 8 GB with Postgres on the
  same host: per dimension the tested ceiling, what degrades first beyond it, the profile, the Studio version and the
  date. A table of targets in that file is read by the summary, which prints `REGRESSION` and fails when a run's
  figure is worse than its target. A run that moves a published figure updates the guide and the README's
  hardware section in the same change.

## Consequences

- A contributor can measure a change on their machine and compare it with the published figures; a maintainer
  can do the same from the Actions tab.
- The figures are of one machine and one workload. They size a deployment; they do not guarantee one. A different
  disk or broker count moves them, and the guide says so.
- Maintaining the test is a cost: a change to an endpoint it calls, or to the shape of the report Gatling writes
  (the summary reads its HTML), can break it. It is not in CI, so a break is found at the next manual run.
- Targets are only checked for the `reference` profile; smaller profiles measure the test.

## Alternatives considered

- **k6.** Scripting in JavaScript is pleasant, but event streams need an extension build, and the repository
  has no other JavaScript load tooling to share.
- **JMeter.** Heavier, XML test plans that review badly, and weaker streaming support.
- **A load test in the main build.** Every contributor would pay for it on every build, and its runs are minutes
  long and need Docker with several gigabytes free.
- **Running it on every pull request.** The measurement needs a quiet machine and several minutes; on shared
  runners it would flap. A manual run before a release, and after a change that touches a hot path, is what is
  affordable.
