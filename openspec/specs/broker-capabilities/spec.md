## Purpose

Defines the capability probe: how Artemis Studio classifies what a given broker
connection can do, the three-state status model, and the contract that an
unavailable feature is always explained and always accompanied by the exact
`broker.xml` change that would enable it.

## Requirements

### Requirement: A connection is classified into four capability classes

For every registered cluster the system SHALL determine, per connection, a
status for each of `MANAGEMENT_READ`, `MANAGEMENT_WRITE`, `NOTIFICATIONS`, and
`MESSAGE_IO`.

#### Scenario: Probe runs at registration

- **WHEN** a cluster is registered or its connection is checked
- **THEN** the response includes a status for all four capability classes

### Requirement: Capability status is three-state with a reason

Each capability status SHALL be exactly one of `AVAILABLE`, `UNAVAILABLE`, or
`UNKNOWN`, and SHALL carry a human-readable reason string. `UNKNOWN` SHALL be
used when Studio cannot determine the capability without taking an action it is
not permitted to take in this phase.

#### Scenario: Reason accompanies every status

- **WHEN** a capability status is returned
- **THEN** it includes a non-empty reason explaining how the status was reached

### Requirement: MANAGEMENT_READ reflects a successful read

`MANAGEMENT_READ` SHALL be `AVAILABLE` when the broker MBean is resolved and a
broker attribute read returns success, and `UNAVAILABLE` otherwise.

#### Scenario: Reads succeed

- **WHEN** the `search` resolves the broker MBean and an attribute read returns 200
- **THEN** `MANAGEMENT_READ` is `AVAILABLE`

#### Scenario: Reads rejected

- **WHEN** attribute reads return 403
- **THEN** `MANAGEMENT_READ` is `UNAVAILABLE` with a reason naming the rejection

### Requirement: MANAGEMENT_WRITE is inferred without mutating the broker

`MANAGEMENT_WRITE` SHALL be determined by invoking a read-only management
operation (`listNetworkTopology()`) and observing whether it is permitted. The
system SHALL NOT create, delete, or modify any broker object to probe write
access. When the inference is positive the reason string SHALL state that it is
an inference and that a per-operation Jolokia policy could still restrict
specific writes.

#### Scenario: Read-only exec succeeds

- **WHEN** `listNetworkTopology()` returns success
- **THEN** `MANAGEMENT_WRITE` is `AVAILABLE` and the reason states it was inferred
  from a read-only operation

#### Scenario: Exec is forbidden

- **WHEN** `listNetworkTopology()` returns a permission error
- **THEN** `MANAGEMENT_WRITE` is `UNAVAILABLE`

#### Scenario: No throwaway objects are created

- **WHEN** the capability probe runs
- **THEN** no address or queue is created or deleted on the broker at any point

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

### Requirement: Every not-available capability ships a broker.xml snippet

When a capability is `UNAVAILABLE`, or `UNKNOWN` for a reason the operator can
fix in configuration, the capability result SHALL include the exact `broker.xml`
snippet that would enable it. The `NOTIFICATIONS` snippets SHALL include both a
`security-setting` for `activemq.notifications` granting `consume`,
`createNonDurableQueue`, and `deleteNonDurableQueue` in one block, and a
`NotificationActiveMQServerPlugin` configuration enabling connection, session,
delivered, and expired notifications.

#### Scenario: Notifications hint is complete

- **WHEN** the `NOTIFICATIONS` capability is returned
- **THEN** it includes a `security-setting` snippet that restates every required
  permission in a single most-specific block, and a plugin snippet with the four
  `SEND_*` flags

#### Scenario: No silent gaps in the UI

- **WHEN** the frontend renders a capability that is not `AVAILABLE`
- **THEN** it shows the reason and the `broker.xml` snippet rather than hiding
  the related controls

### Requirement: Native slow-consumer detection is reported three-state with its snippet

The system SHALL report whether the broker's own slow-consumer detection is configured,
using the same three-state grammar as every other capability: configured, not configured,
or unknown. When the broker's management surface does not expose the slow-consumer
threshold at all, the state SHALL be **unknown** — never "not configured" — because
Studio cannot tell the difference, and reporting the difference it cannot observe would
be a guess.

Where the state is not "configured", the result SHALL include the exact `broker.xml`
snippet that enables native slow-consumer detection, including the threshold, the check
period, and the policy.

#### Scenario: Threshold not exposed

- **WHEN** the broker's address-settings read does not return a slow-consumer threshold
- **THEN** native slow-consumer detection is reported as unknown, with the enabling
  `broker.xml` snippet

#### Scenario: Detection configured

- **WHEN** the broker returns a slow-consumer threshold, check period, and policy
- **THEN** native slow-consumer detection is reported as configured, with those values

#### Scenario: Detection off

- **WHEN** the broker exposes the slow-consumer threshold and reports it disabled
- **THEN** native slow-consumer detection is reported as not configured, with the
  enabling `broker.xml` snippet
