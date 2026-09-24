## ADDED Requirements

### Requirement: Email, Microsoft Teams and PagerDuty are notification channel kinds

In addition to Slack and signed webhooks, the system SHALL support three more
notification channel kinds, each delivering through the same durable, retried,
once-per-tick delivery path:

- an **email** channel, sent over SMTP;
- a **Microsoft Teams** channel, sent to a Teams webhook URL;
- a **PagerDuty** channel, sent to a PagerDuty Events API v2 endpoint.

A channel's secret material SHALL be encrypted at rest and never returned: the SMTP
password, the Teams webhook URL, and the PagerDuty routing key.

#### Scenario: A PagerDuty channel is created

- **WHEN** an operator creates a PagerDuty channel with a routing key
- **THEN** the channel is stored, reading it back shows that a secret is configured
  without revealing it, and rules can be bound to it

#### Scenario: An unknown kind is rejected

- **WHEN** a channel is submitted with a kind that is not one of the five supported kinds
- **THEN** the request is rejected, naming the kind

### Requirement: A channel's configuration is validated per kind before it is stored

The system SHALL validate a channel's configuration for its kind on create, update
and test, and SHALL reject an invalid one with a message that names the field at
fault:

- A URL SHALL use `http` or `https` and name a host.
- An email channel SHALL name an SMTP host, a port between 1 and 65535, a transport
  security mode (STARTTLS, TLS or none), a valid sender address, and at least one
  valid recipient address.
- A PagerDuty channel that uses the default endpoint SHALL have a 32-character
  routing key.

#### Scenario: An email channel without recipients is rejected

- **WHEN** an operator saves an email channel with no recipient address
- **THEN** the request is rejected and the message names the recipients field

#### Scenario: A malformed recipient is rejected

- **WHEN** an email channel lists a recipient that is not a valid address
- **THEN** the request is rejected, naming that address

### Requirement: An email channel sends over SMTP without downgrading its security

An email channel SHALL send one message per delivery. The message SHALL have a
subject naming the severity, the rule and the cluster, and a body listing every
transition in the delivery, as both HTML and plain text. When the channel requires
STARTTLS, a server that does not offer it SHALL cause the delivery to fail rather
than be sent unencrypted.

- An authentication failure SHALL be permanent.
- A delivery whose every recipient is rejected SHALL be permanent.
- A connection or I/O failure SHALL be retried.

Values from a rule or a subject SHALL NOT be able to add or alter a message header.

#### Scenario: Wrong SMTP credentials are not retried

- **WHEN** the SMTP server rejects the channel's credentials
- **THEN** the delivery is marked permanently failed on the first attempt

#### Scenario: A rule name cannot inject a header

- **WHEN** a rule's name contains a line break followed by header text
- **THEN** the sent message's subject contains no line break and no extra header is
  added

### Requirement: A Teams channel delivers an Adaptive Card

A Teams channel SHALL deliver each notification as an Adaptive Card message. The card
SHALL state the severity in words, and SHALL name the cluster, the rule and each
transition. When a link to Studio is available, the card SHALL offer it as an action.
A response saying the webhook no longer exists, or is not authorised, SHALL be
permanent. A response asking the sender to slow down SHALL be honoured before the
next retry.

#### Scenario: A deleted Teams workflow is not retried

- **WHEN** the Teams webhook responds that it was not found
- **THEN** the delivery is marked permanently failed without retrying

### Requirement: A PagerDuty channel opens and resolves one incident per firing subject

A PagerDuty channel SHALL send one Events API v2 event per transition in a delivery:
a `trigger` for a subject that started firing, and a `resolve` for a subject that
resolved. Each event SHALL carry a deduplication key derived from the rule and the
subject, so a subject's resolve closes the incident its trigger opened, and resending
the same delivery opens no second incident. The event severity SHALL follow the rule's
severity. The endpoint SHALL default to PagerDuty's, and SHALL be configurable per
channel for another service region or a PagerDuty-compatible receiver.

- A response rejecting the event as malformed SHALL be permanent.
- A rate-limit response SHALL be retried no sooner than the receiver asks.

#### Scenario: A resolution closes the incident

- **WHEN** a subject that fired resolves
- **THEN** PagerDuty receives a `resolve` event with the same deduplication key as the
  `trigger` it received when the subject fired

#### Scenario: A retried delivery opens no duplicate incident

- **WHEN** a delivery carrying three firings fails on its third event and is retried
- **THEN** the retry resends all three with the same deduplication keys, and PagerDuty
  still holds one incident per subject

### Requirement: A notification can be read without opening Studio

Every notification, whatever its channel kind, SHALL name the cluster by name. It SHALL
name each subject in readable form: a node by its name, not an internal identifier,
and a queue by its name. It SHALL state the observed value where there is one, and
the time of each transition. When a public URL for Studio is configured, it SHALL
include a link to the cluster's alerts. The generic webhook payload SHALL carry these
as additional fields, and SHALL keep every field it carried before with an unchanged
meaning.

#### Scenario: A node subject is named

- **WHEN** a node-down rule fires for a node
- **THEN** the notification names the node, not the identifier Studio stores for it

#### Scenario: Existing webhook receivers keep working

- **WHEN** a webhook receiver written against the previous payload receives a delivery
- **THEN** the fields it reads are present, with the same names and meaning

### Requirement: A channel can be tested before it is saved, and the test reports its outcome

The system SHALL let an operator send a test notification using a configuration that
has not been saved yet. When editing an existing channel and leaving the secret
blank, the test SHALL use the stored secret. A test SHALL report whether the
notification was delivered and, if not, the receiver's or the transport's stated
cause and whether retrying could help. An unsuccessful test SHALL be reported as a
result, not as an error of the request. Every test SHALL be audited.

#### Scenario: A wrong URL is caught before saving

- **WHEN** an operator tests an unsaved webhook configuration whose receiver answers 404
- **THEN** the test reports not delivered, with the receiver's status, and nothing is
  saved

### Requirement: A channel shows its delivery health and log

Each channel SHALL show the outcome and time of its most recent delivery attempt, its
most recent error, the number of deliveries waiting, and how many failed and
succeeded in the last 24 hours. It SHALL also show the rules it is bound to by count.
An operator SHALL be able to open a channel's delivery log, newest first, where each
entry shows:

- the rule;
- a one-line summary;
- the state (waiting, sent or failed);
- the number of attempts;
- the last error;
- the creation, next-attempt and delivered times.

#### Scenario: A failing channel is visible without opening it

- **WHEN** a channel's last delivery failed permanently
- **THEN** the channel list shows that it failed, when, and why

### Requirement: A permanently failed delivery can be retried

The system SHALL let an operator return a permanently failed delivery to the queue.
It SHALL then be attempted again, with its attempt count reset. Retrying a delivery
that is not permanently failed SHALL be refused. A retry SHALL be audited.

#### Scenario: A delivery that failed while the receiver was down is retried

- **WHEN** a delivery was marked permanently failed and the operator retries it after
  fixing the channel
- **THEN** the dispatcher attempts it again on its next pass

### Requirement: Deleting a channel states what it is bound to

Deleting a notification channel SHALL be confirmed by typing the channel's name.
Before it can be confirmed, the operator SHALL be told how many rules are bound to the
channel and will stop delivering to it.

#### Scenario: Deleting a bound channel

- **WHEN** an operator starts deleting a channel bound to three rules
- **THEN** the confirmation states that three rules will stop delivering to it, and
  the delete is not armed until the channel's name is typed

### Requirement: Setup risk is an alertable cluster-state condition

The system SHALL provide a cluster-state alert condition, `SETUP_RISK`. It SHALL be
active for each open finding of the cluster setup review with critical or warning
severity that has not been accepted as a known risk, or whose acceptance has expired.
Each finding SHALL be its own subject, so that each is tracked independently. A
finding about a node the last review could not read SHALL remain in the evaluation,
so that an unreachable node cannot resolve it. A finding the review no longer
produces SHALL resolve. The condition SHALL ship as a rule template, not a seeded
rule.

#### Scenario: A new critical finding fires

- **WHEN** a review records a critical finding and a `SETUP_RISK` rule exists for the
  cluster
- **THEN** the rule fires for that finding once its debounce elapses

#### Scenario: Accepting a risk resolves its firing

- **WHEN** an operator accepts a firing finding as a known risk
- **THEN** the firing for that finding resolves on the next evaluation

#### Scenario: A fixed configuration resolves

- **WHEN** a later review no longer produces a finding
- **THEN** its firing resolves
