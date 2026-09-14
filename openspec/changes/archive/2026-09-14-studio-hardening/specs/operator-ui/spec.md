## ADDED Requirements

### Requirement: A previewed mutation is exactly what is submitted

Where a mutation is previewed before it is confirmed, as when creating a divert, the values
previewed SHALL be exactly the values submitted. While a preview is shown its inputs SHALL
NOT be editable. Changing a value SHALL require leaving the preview, and the preview SHALL
be run again before the mutation can be confirmed.

The form SHALL validate each field on blur against the same rules the server enforces, and
SHALL show any per-field error the server returns beside that field. On a rejected
submission, focus SHALL move to the first invalid field. A control that cannot yet be used
SHALL state why.

The dialog SHALL NOT be dismissible while its mutation is in flight. The outcome — succeeded,
failed, or partial with per-node detail — SHALL be shown once the mutation settles.

#### Scenario: Editing after preview requires a new preview

- **WHEN** an operator previews a divert and then chooses to change its forwarding address
- **THEN** the preview is left, the fields become editable, and the creation cannot be confirmed until a new preview has run

#### Scenario: A server field error is shown beside its field

- **WHEN** the server rejects a divert because its name contains an unsafe character
- **THEN** the error is shown beside the name field and focus moves to it

#### Scenario: The outcome of an in-flight mutation is not lost

- **WHEN** an operator tries to close the divert dialog while its creation is in flight
- **THEN** the dialog stays open, and shows the per-node outcome when the creation settles

### Requirement: Arming a capture subscription is confirmed against its dry run

The interface SHALL render the dry run before a capture subscription can be armed. The dry
run shows:
- the resolved addresses;
- the target nodes;
- the capture queue's bounds in messages and bytes;
- the broker objects to be created;
- the equivalent broker configuration.

Arming a capture subscription SHALL require typing the pattern. Its bounds — message and
byte limits, per-message body limit, ingest rate and filter — SHALL be editable behind a
disclosure, validated on blur against the server's limits. A value outside those limits
SHALL be refused with its reason, never silently adjusted.

The copy SHALL match the subscription's mode: a captured subscription SHALL NOT be described
as sampled, and a sampled one SHALL NOT be described as capturing everything.

#### Scenario: Capture cannot be armed without its dry run and typed pattern

- **WHEN** an operator starts creating a capture subscription
- **THEN** the dry run is shown and the action cannot be armed until the pattern has been typed

#### Scenario: An out-of-range bound is refused, not clamped

- **WHEN** an operator enters a capture queue bound above the server's maximum
- **THEN** the field shows the maximum and the reason, and the value is not silently changed

#### Scenario: Copy follows the mode

- **WHEN** an operator views a subscription in capture mode
- **THEN** no text on it describes the subscription as sampled

### Requirement: Per-node outcomes and errors are announced

A per-node outcome summary SHALL be announced to assistive technology when it appears or
changes, and an error that stops an action SHALL be announced as an alert. This applies to
the preview and to the result.

#### Scenario: A partial outcome is announced

- **WHEN** a divert creation settles with a partial outcome
- **THEN** the per-node summary is announced by assistive technology without the operator moving focus to it

### Requirement: An unavailable figure is stated, never shown as zero

Where a count, total or estimate that a view would show is unavailable, the view SHALL state
that it is unavailable and why. This covers a browse total, a capture loss estimate, and a
capture footprint. It SHALL NOT show zero, a dash that reads as zero, or omit the figure.

#### Scenario: A browse without a total says so

- **WHEN** a browse response states that its total is unavailable
- **THEN** the message view says the total is unavailable and why, and shows no number in its place

#### Scenario: A filtered capture's loss says it cannot be estimated

- **WHEN** a capture subscription's loss estimate is unavailable
- **THEN** the subscription view says the estimate is unavailable and why

### Requirement: A capture deletion states what happens on unreachable nodes

Before a capture subscription's deletion can be armed, the interface SHALL state that broker
objects are removed from reachable nodes immediately, and from unreachable nodes on their
next reconciliation. After the deletion, it SHALL show the per-node removal outcome.

#### Scenario: An unreachable node's cleanup is stated

- **WHEN** an operator deletes a capture subscription while one of its nodes is unreachable
- **THEN** the confirmation states that a node that does not answer is cleaned on its next reconciliation, rather than claiming every node was cleaned

### Requirement: Sampling is named as sampling where capture would see more

Wherever a sampled subscription is chosen or listed, the UI SHALL say briefly that it is only
sampling and that a message consumed between two polls is not recorded.

Where request-reply tracing depends on addresses that no enabled capture subscription covers,
the UI SHALL show a short hint naming those addresses and where capture is turned on. The hint
SHALL disappear once every such address is captured.

#### Scenario: A traced address that is only sampled gets a hint

- **WHEN** an operator traces a request address and a reply address, and neither is captured
- **THEN** the tracing screen names both as only sampled and points to where capture is turned on

#### Scenario: The hint goes away once capture covers the addresses

- **WHEN** an enabled capture-everything subscription covers every traced address
- **THEN** no capture hint is shown

#### Scenario: A sampled subscription says it is only sampling

- **WHEN** an operator views or creates a sampled subscription
- **THEN** it is described as just sampling, which misses messages consumed between polls
