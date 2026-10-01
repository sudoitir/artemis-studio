# ADR-0154: Plugins read metric history as a named user, through the metrics read API's own checks

- **Status**: accepted
- **Date**: 2026-10-01
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/15b-plugin-sign-in-and-metric-history`
- **Builds on**: [ADR-0033](0033-metric-read-model.md), [ADR-0111](0111-plugin-scoped-beans-and-plugin-messaging.md), [ADR-0113](0113-plugins-publish-metrics-through-metric-sources.md)

## Context

Plugins publish metrics (ADR-0113), but cannot read the history Studio keeps: `MetricQueryService` is
not plugin API, and it checks the principal of the current request, which a plugin's background work
does not have. Plugin messaging already acts for a named user (ADR-0111), re-reading that user's
account and grants on each use.

## Decision

- A new `@PluginApi` bean, `MetricHistory`, in the metrics feature:
  - `read(actingUserId, clusterId, MetricQuery)` for the queue and cluster metrics;
  - `readPluginMetric(actingUserId, clusterId, metric, subject, from, to, step)` for any running
    plugin's declared metric.
- The acting user is resolved by `OperatorHandoff.forUser`: the account as it stands now, with grants
  re-read from the database. A missing, unknown or disabled user answers exactly as a cluster that
  does not exist.
- The read runs as that user (`OperatorHandoff.callAs`) through `MetricQueryService` itself. The
  cluster check, the metric's declared permission, the retention clamp, the point cap and the
  `truncated` flag are therefore the REST API's.
- `MetricQuery` and the `MetricViews` records become `@PluginApi`, so japicmp guards the shape plugins
  receive.

## Consequences

- One read path for the UI, MCP and plugins. A change to the clamps applies to all of them.
- A plugin's background reads stop as soon as their user loses access, with no session involved.
- The REST response records are now plugin API: changing them incompatibly bumps the contract.
- A plugin may name any user as the acting user, as in plugin messaging. The API keeps it honest
  about whose permissions apply; it does not sandbox trusted code.

## Alternatives considered

- **A plugin-specific reader with its own result types.** That would mean a second copy of the clamps
  and the checks, and a second shape for series the SDK already draws.
- **Read as the plugin, with permissions of its own.** Plugins have no grants. Background work
  that acts for nobody would bypass cluster permissions entirely.
