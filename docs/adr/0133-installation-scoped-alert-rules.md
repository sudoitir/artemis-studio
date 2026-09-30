# ADR-0133: Alert rules can be scoped to the installation

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Mahdi Amirabdollahi

## Context

Every alert rule belonged to a cluster (`alert_rule.cluster_id NOT NULL`), and evaluation took a
cluster id. Storage quotas and table health are about Studio's own database, not about a broker.
Seeding such a rule per cluster would fire one alert N times, and an installation with no cluster
would get none.

## Decision

- `alert_rule.cluster_id` is nullable. A rule without a cluster is **installation-scoped**, and so
  are its state and firings.
- `InstallationSignalSource` beans evaluate installation conditions, the way `AlertSignalSource`
  beans evaluate cluster ones. `AlertEvaluator.evaluateInstallation(kind)` runs the matching
  enabled installation rules through the same debounce, history and delivery as any rule.
- The data lifecycle ([ADR-0132](0132-one-data-lifecycle-for-every-store.md)) provides
  `STORAGE_QUOTA` (a store over its quota warning) and `STORAGE_HEALTH` (a table unhealthy or a
  partition missing). One rule of each is seeded per installation: enabled, bound to no channel,
  and editable like any rule.
- The alerts screen shows "Installation" where a cluster would be.

## Consequences

- Studio can alert about itself, once, whatever the number of clusters.
- Code that assumed every rule has a cluster handles `null`: the rule list, the firing history and
  the delivery message.
