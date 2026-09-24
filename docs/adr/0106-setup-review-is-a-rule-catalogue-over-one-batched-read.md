# ADR-0106: Setup review is a rule catalogue over one batched read

- **Status**: accepted
- **Date**: 2026-09-24
- **Deciders**: Artemis Studio maintainers

## Context

The Artemis mistakes that hurt most are silent until the failover that exposes them.
The dev pair is the textbook case: one replication pair on quorum voting. Artemis
2.44's `QuorumManager` skips the vote when the cluster has only ever had one primary,
so on a network partition the backup always promotes itself. Other silent mistakes:

- load balancing that relies on redistribution while `redistribution-delay` is `-1`;
- a connector advertising `localhost` to the cluster;
- a cluster connection that never met a member;
- persistence off, unbounded disk, or no dead-letter address.

Studio already has the inputs over the management API: the broker MBean, the default
address settings, and the cluster-connection MBeans. `brokerconfig` compares nodes
against each other and against a declaration. Nothing judges the configuration against
what is known to go wrong.

The management API does not expose everything. `network-check-list`, `quorum-size`,
`vote-on-replication-failure` and `check-for-active-server` are invisible to it.

## Decision

We will add a module, `feature.setupreview`, that:

1. **Reads each manageable node in one batched Jolokia POST**, through the node's rate
   limiter. The batch holds the broker MBean (`readAll`),
   `getAddressSettingsAsJSON("#")`, and a `readAll` of
   `component=cluster-connections,name=*`. It runs on a slow interval (default 15
   minutes), under its own `ClusterLock` scope, and on demand with a minimum spacing.
2. **Evaluates a pure, versioned catalogue of rules** over those reads and over
   Studio's topology. Each rule has a fixed code, category and severity, and each
   finding carries its evidence per node, its impact, its recommendation and a
   `broker.xml` fragment.
3. **Persists the latest findings per cluster** and replaces them only for the subjects
   the run could evaluate. A finding about a node that did not answer is kept and
   marked not re-checked. Anything a rule depends on that the API does not expose is
   stated in the finding, never assumed.
4. **Lets an operator accept a finding as a known risk.** This needs a reason, may carry
   an expiry, and is audited. The finding stays visible as accepted.
5. **Contributes `SETUP_RISK` through `AlertSignalSource`**, evaluated from the
   persisted findings, like `CONFIG_DRIFT`. It is offered as a template, not seeded.

The review never writes to a broker. Where a fix is an address setting that Broker
configuration can apply, the finding links there.

## Consequences

- There is one more batched read per node per interval. It is cheap next to the
  scrape tiers, and bounded by the limiter.
- A catalogue grows by adding a rule and its tests. The tests pin each rule to the
  attribute shapes verified against Artemis sources. An unrecognised `HAPolicy`
  string is "not assessed", never guessed.
- Some criticals will already be mitigated by settings Studio cannot see (a
  `network-check-list`). The finding says so, and acceptance with a reason is the
  path. We prefer a stated, acceptable false positive to a silent false negative.
- Findings are a cache of the last review, like `queue_snapshot`. Losing them costs one
  review. Acceptances are operator decisions, and are not a cache.

## Alternatives considered

- **Fold the review into `brokerconfig`'s drift job.** Drift compares against a
  declaration that most clusters do not have, and its job runs only for declared
  clusters. The review must run for every cluster.
- **Read `broker.xml` from the broker's filesystem.** Studio has no file access to
  brokers and must not need it. Jolokia is the channel.
- **Evaluate the rules in the alert evaluator.** That would put broker I/O inside the
  evaluator's transaction, which ADR-0015 forbids.
- **A numeric "health score".** It invites optimising the number over the risk. The
  review reports counts per severity, in words.
