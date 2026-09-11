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

#### Scenario: A snippet the declaration can apply links there

- **WHEN** a registered cluster's capability snippet consists, wholly or in part, of address or security settings
- **THEN** the ledger states which part the declared configuration can apply over the management API and which still needs `broker.xml`, and links into the cluster's configuration with the snippet offered for import

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

### Requirement: A node's effective configuration is readable on its own

The system SHALL report the configuration one node is effectively running with —
broker attributes, address settings, security settings and acceptors, as the broker
resolves them — addressable by that node alone, without requiring a second node to
compare it against.

This SHALL be the broker's resolved runtime configuration, never the contents of a
configuration file: the system does not read or write `broker.xml`.

A node that cannot be read SHALL be reported as unavailable with the reason. An
empty configuration SHALL NOT be returned in its place, because an absence
presented as a fact is indistinguishable from a node that is genuinely configured
with nothing.

Where the number of address-setting matches resolved is capped, the response SHALL
state how many were read of how many were known.

#### Scenario: One node's settings are readable alone

- **WHEN** an operator asks what a single node is configured with
- **THEN** its effective configuration is returned without naming a second node

#### Scenario: An unreadable node is not reported as unconfigured

- **WHEN** a node's configuration cannot be read
- **THEN** the result says so and gives the reason, rather than returning an empty
  configuration

### Requirement: Message capture is a capability, reported three-state with its snippet

The system SHALL report, per connection, whether it can install a message capture tap. The
answer SHALL be three-state — available, not available, or not yet established — on the same
terms as every other capability.

Capture requires more than management write: it requires the authority to manage diverts, to
create an address and a queue, to set an address setting, and to restrict access to the
address it creates. Where any of these is refused, the system SHALL name **which** one was
refused, because they are granted separately and an operator told only "capture is
unavailable" cannot act on it.

Each unavailable element SHALL ship the broker configuration that would grant it.

Capture SHALL NOT be blocked because the capability has not yet been established. An
unattempted capability is unknown, not unavailable, and the attempt itself is what
establishes it.

#### Scenario: A refused element is named with its remedy

- **WHEN** Studio's broker identity may manage diverts but may not set a security setting on the address it would create
- **THEN** the capability reports that specific refusal and ships the configuration that would grant it

#### Scenario: An unestablished capture capability does not block the operator

- **WHEN** no capture has been attempted on a connection
- **THEN** the capability is reported as not yet established and the operator is still offered the action
