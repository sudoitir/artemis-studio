# Proposal: cluster-onboarding-and-polish

## Why

Registering a cluster needed one management URL per broker, because `listNetworkTopology()`
advertises Core connectors, never management URLs. The setup also asked for roles Studio could read,
left adoption for later, could not edit a connection once it existed, and reported a rejected
credential as an unreachable broker. The config diff compared two nodes at a time and listed every
key, so real drift was hard to find. Long names, the SQL Console's results and plugin forms broke
their layouts.

## What Changes

- One-URL registration: management URLs are derived from a pattern and proved by `NodeID` (ADR-0175).
- The management and Core accounts are checked separately, per node, and a rejected credential is
  named in health.
- Recommended roles are read from Studio's broker account (ADR-0177).
- Adoption is a step of registration (ADR-0176).
- **BREAKING** A Connection section and `PATCH /clusters/{id}` replace the credentials PUT.
- **BREAKING** The config diff compares every node against the majority (ADR-0178).
- Long names stay on one line, the SQL Console's results fill the window and can be maximised, and
  the SDK exports `FieldRow` and `Notice`.

## Capabilities

### Modified Capabilities

- `cluster-registration`, `cluster-topology`, `broker-connectivity`, `broker-configuration`,
  `broker-config-diff`, `studio-settings`, `operator-ui`.

## Impact

`platform/clusters`, `platform/broker`, `feature/brokerconfig`, `features/clusters`,
`features/brokerconfig`, `features/sql`, `ui/` and the plugin SDK. Implemented in PRs #205 to #210.
