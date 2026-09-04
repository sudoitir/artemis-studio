## RENAMED Requirements

- FROM: `### Requirement: NOTIFICATIONS is UNKNOWN with detectable preconditions reported`
- TO: `### Requirement: NOTIFICATIONS reflects the Core subscription outcome`

## MODIFIED Requirements

### Requirement: NOTIFICATIONS reflects the Core subscription outcome

`NOTIFICATIONS` SHALL be a determined verdict, not a fixed `UNKNOWN`. The system
SHALL derive it from the outcome of the cluster's Core subscription rather than
by opening a connection during the capability read:

- `AVAILABLE` when Studio holds at least one open subscription to
  `activemq.notifications` for the cluster. The reason SHALL note that
  connection, session, delivered, and expired events additionally require
  `NotificationActiveMQServerPlugin`, and the snippet for that plugin SHALL be
  shipped, because an idle broker cannot be distinguished from one missing the
  plugin.
- `UNAVAILABLE` when a subscription was attempted and refused for lack of
  permission, with the `activemq.notifications` security-setting snippet
  (`consume` and `createNonDurableQueue`).
- `UNAVAILABLE` when no serving node has a resolvable Core URL, with the CORE
  acceptor snippet.
- `UNAVAILABLE` with the classified connection error for any other subscription
  failure.
- `UNKNOWN` only until the first scrape cycle has produced a subscription
  outcome.

The probe SHALL still report the Jolokia-visible preconditions — whether a
CORE-protocol acceptor and the `activemq.notifications` address exist — within
the reason text.

#### Scenario: Subscribed

- **WHEN** Studio holds an open `activemq.notifications` subscription for the cluster
- **THEN** `NOTIFICATIONS` is `AVAILABLE` and the reason ships the notification-plugin snippet with the note that an idle broker looks the same as one missing the plugin

#### Scenario: Subscription refused for permission

- **WHEN** the broker refuses the subscription because the user lacks `consume` or `createNonDurableQueue` on `activemq.notifications`
- **THEN** `NOTIFICATIONS` is `UNAVAILABLE` and the reason ships the security-setting snippet naming both permissions

#### Scenario: No Core URL

- **WHEN** no serving node of the cluster has a resolvable Core URL
- **THEN** `NOTIFICATIONS` is `UNAVAILABLE` and the reason ships the CORE acceptor snippet

#### Scenario: Not yet determined

- **WHEN** the first scrape cycle for a newly registered cluster has not completed
- **THEN** `NOTIFICATIONS` is `UNKNOWN` with a reason saying the first probe has not run, and the reason still reports the Jolokia-visible preconditions

#### Scenario: Capability read does not open a connection

- **WHEN** a capability read is served for a cluster
- **THEN** it returns the cached subscription verdict without opening a Core connection during the request

#### Scenario: Preconditions visible

- **WHEN** the broker has a CORE acceptor and the `activemq.notifications` address and Studio holds an open subscription
- **THEN** `NOTIFICATIONS` is `AVAILABLE` and the reason notes both preconditions are present alongside the notification-plugin snippet

#### Scenario: Precondition missing

- **WHEN** the broker exposes no CORE-protocol acceptor
- **THEN** `NOTIFICATIONS` is `UNAVAILABLE` and the reason notes the CORE acceptor is absent
