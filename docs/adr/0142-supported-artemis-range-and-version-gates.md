# ADR-0142: A stated Artemis range, checked at registration, tested at both ends, gated per node

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/08-artemis-version-support`

## Context

Studio's integration tests ran against one broker image, `apache/activemq-artemis:2.44.0`, while it
ships the 2.57.0 client, and ADRs and notes cite 2.39, 2.44 and 2.56. No range was stated. An operator
could not tell whether their broker was supported, and one older than anything tested failed in the
middle of a feature, with a Jolokia error, rather than when it was registered. Artemis also became its
own Apache project: 2.50 and later ship as `apache/artemis`, and the old image line ends at 2.44.0.

Studio already read every node's `Version` attribute (topology discovery and the tier-A scrape) and
stored it, but never compared it.

## Decision

- **The supported range is 2.33.0 to 2.57.0**, held in `BrokerVersion.MINIMUM` and `LATEST_TESTED`.
  The minimum is the first release with every management operation Studio's core features call: the
  thirteen-argument `addSecuritySettings`, which carries the view and edit permissions that capture,
  plugin taps and configuration edits set, arrived in 2.33.0 (the JSON `addAddressSettings` they also
  use arrived in 2.32.0). It was established by reading the broker's `ActiveMQServerControl` at each
  release tag and by running the integration suite against 2.32.0, where those features failed on
  exactly that call, then against 2.33.0. Falling back to the eleven-argument form would silently drop
  the view and edit permissions, so it is not offered.
- **An operation newer than the minimum falls back when it can.** The JSON `createDivert(json)` arrived
  in 2.38.0; on the broker's "No operation createDivert(java.lang.String)" error, `DivertOperations`
  sends the positional arm with the same fields. The fallback costs nothing on a current broker.
  Two list details work the same way: an unfiltered `listX` sends `BrokerListOps.ALL` (a filter with
  an empty field) instead of the empty string older releases fail to parse, and Flow's filtered-queue
  read falls back to an unfiltered one where the `NOT_EQUALS` filter operation does not exist yet.
- **An operation that cannot fall back is a `VersionGate`**: the first release that has it. Gates are
  assessed per node from the recorded version, shaped like a capability (`CapabilitiesView.versionGates`),
  so the UI disables the control with the release named. `BrokerCommands.Command.requires` skips an older
  node with `NodeStatus.UNSUPPORTED_VERSION`, a skip rather than a failure, so a mixed cluster keeps the
  operation on the nodes that have it. There is no gate today.
- **Registration reads each seed's version before storing anything** and refuses one below the minimum
  with `BrokerConnectionException.Kind.UNSUPPORTED_VERSION` (422), naming the minimum; a dry run gives
  the same verdict. A release newer than tested is accepted with a warning. Each node's
  `versionSupport` is derived on read, so an upgrade or a node found later needs no new write path; a
  later node below the minimum is flagged and the cluster stays registered.
- **CI runs the backend suite at both ends** of the range (a matrix over the three shards), with the
  image from `-Dartemis.image`. `BrokerVersionTest` fails when the matrix and the constants disagree.

## Consequences

- Operators get a documented range (`site/src/guide/supported-versions.md`) and a refusal at
  registration instead of a mid-feature failure.
- The backend job runs on six runners instead of three; they run in parallel.
- `BrokerCommands.Command` is plugin API, and its new `requires` component changes its canonical
  constructor, so the plugin contract moves to 6 (ADR-0112) and plugins are rebuilt against it. A
  plugin's commands can declare a version gate like Studio's own.
- Moving the range means changing the two constants, the CI matrix and the guide page; the test
  catches the first two drifting apart.
- The positional `createDivert` arms are deprecated for removal (2.57 still has them). The fallback only runs on a
  broker without the JSON arm, so a future removal does not affect it; if the minimum rises to 2.38,
  the fallback goes.
- The earlier measurement notes stay tied to the release they were measured on.

## Alternatives considered

- **Minimum 2.38.0.** No fallback, but it refuses brokers from 2.33 to 2.37, which run everything
  once divert creation falls back.
- **Minimum 2.44.0**, what was already tested. It shuts out more than a year of releases for no
  technical reason.
- **Gating capture, plugin taps and divert creation below 2.38** instead of falling back. It is less
  code, but it switches off a large part of the product on brokers that can do the work.
- **Probing capabilities per node.** A version gate needs only the recorded version; the live probe
  stays per cluster.
