# ADR-0135: Alert rules can be scoped to the installation

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Mahdi Amirabdollahi

## Context

Every alert rule belonged to a cluster in practice: rules were only ever created through a
cluster, every firing required one (`alert_firing.cluster_id NOT NULL`), and evaluation took a
cluster id. Storage quotas and table health are about Studio's own database, not about a broker.
Seeding such a rule per cluster would fire one alert N times, and an installation with no cluster
would get none.

## Decision

- A rule without a cluster (`alert_rule.cluster_id IS NULL`) is **installation-scoped**, and so are
  its state and firings (`alert_firing.cluster_id` becomes nullable, changeset `feature-alerting 0005`).
- `InstallationSignalSource` beans evaluate installation conditions, the way `AlertSignalSource`
  beans evaluate cluster ones. `AlertEvaluator.evaluateInstallation(kind)` runs the matching
  enabled installation rules through the same debounce, history and delivery as any rule.
- The data lifecycle ([ADR-0134](0134-one-data-lifecycle-for-every-store.md)) provides
  `STORAGE_QUOTA` (a store over its quota warning) and `STORAGE_HEALTH` (a table unhealthy or a
  partition missing). One rule of each is seeded by the same changeset, so once per database and
  never again: enabled, bound to no channel, editable like any rule, and not recreated once an
  operator deletes it.
- Installation rules and firings appear in every cluster's alerts view, marked "Installation". Only
  a global `alert:read` / `alert:write` grant sees or changes them, so a cluster-scoped operator
  cannot silence them.
- The alerts screen shows "Installation" where a cluster would be.

## Consequences

- Studio can alert about itself, once, whatever the number of clusters.
- Code that assumed every rule has a cluster handles `null`: the rule list, the firing history and
  the delivery message.
