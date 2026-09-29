## ADDED Requirements

### Requirement: A report is scheduled with a format and content
The system SHALL let a user define a report of queue depth, alerts, SLOs or an audit summary, choose CSV or JSON and a schedule, and preview it before saving.

#### Scenario: A weekly report
- **WHEN** a user schedules queue depth weekly
- **THEN** the report runs weekly and the first preview matches its content

#### Scenario: A user lacks permission for the data
- **WHEN** the user cannot read the audit log
- **THEN** they cannot define an audit report

### Requirement: Reports are delivered through existing channels
A report SHALL be delivered to a distribution list over the notification channels that can carry attachments, and the definition SHALL be refused if its channel cannot.

#### Scenario: Delivery
- **WHEN** a report runs
- **THEN** every recipient in the list receives the attachment

#### Scenario: Channel cannot attach
- **WHEN** the chosen channel cannot carry a file
- **THEN** the definition is refused with the reason

#### Scenario: An outside recipient
- **WHEN** a distribution list names an address outside the domains an administrator allows
- **THEN** the definition is refused naming the address, so reports cannot be sent out of the organisation

### Requirement: A report can be attached to an alert
A firing alert SHALL be able to include a chosen report generated at that time.

#### Scenario: Alert with report
- **WHEN** a bound rule fires
- **THEN** the notification carries a fresh report

### Requirement: Each run happens once per installation
Across multiple Studio instances, a scheduled run SHALL execute and deliver exactly once.

#### Scenario: Two instances
- **WHEN** two instances share a database
- **THEN** a due report is delivered once

### Requirement: Delivery history is recorded and audited
Every run SHALL record time, recipients, size and outcome, be visible in a history, and create and change of report definitions SHALL be audited. Report content SHALL NOT be stored in the audit log.

#### Scenario: A delivery fails
- **WHEN** a channel returns an error
- **THEN** the history shows the failure and its retries

### Requirement: Reports never exceed the reader's rights or leak masked data
A report SHALL contain only data the definition's owner may read and SHALL apply masking rules.

#### Scenario: Owner loses access
- **WHEN** the owner's permission is removed
- **THEN** the report stops and the history says why
