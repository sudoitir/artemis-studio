## ADDED Requirements

### Requirement: A rule can alert on a rate of change
The engine SHALL support a condition on how fast a metric changes over a window, evaluated per matching subject, in addition to its current value.

#### Scenario: A backlog grows fast
- **WHEN** the depth of a queue grows faster than the rule's rate over its window
- **THEN** the rule fires for that queue

#### Scenario: Too little history
- **WHEN** a window has too few samples
- **THEN** the rule reports it cannot evaluate and does not fire

### Requirement: A rule can alert on absence
The engine SHALL support a condition that fires when expected data or consumption is absent for a duration, and SHALL distinguish absence of data from a value of zero.

#### Scenario: A consumer stops
- **WHEN** a queue with a consumer shows no consumption for the duration
- **THEN** the rule fires as absence

#### Scenario: A scrape is missing
- **WHEN** no samples arrive for a subject
- **THEN** the rule fires as no-data, not as a zero value

### Requirement: A rule can require a duration and use hysteresis
A rule SHALL be able to require its condition for a duration before firing and use separate thresholds to fire and to resolve, so a value near the limit does not flap.

#### Scenario: A value oscillates around the limit
- **WHEN** it crosses the fire threshold and returns between the fire and resolve thresholds
- **THEN** the alert stays firing and does not resolve and fire repeatedly

### Requirement: Alerts are routed by labels
Each firing SHALL carry labels including cluster, environment and severity, and a routing tree SHALL choose channels by matching them, with a default route for an alert that matches nothing.

#### Scenario: A critical alert in production
- **WHEN** an alert has severity critical and a production environment
- **THEN** it reaches the channels of the matching route

#### Scenario: An alert matches no route
- **WHEN** no route matches
- **THEN** it goes to the default route and is never dropped

### Requirement: Alerts can be silenced and suppressed by maintenance windows
A user with the matching permission SHALL be able to silence alerts matching labels for a time span, and to define maintenance windows, and Studio SHALL record who did so and why. Silenced alerts SHALL remain in the history marked as silenced.

#### Scenario: A window is active
- **WHEN** an alert fires inside a maintenance window for its cluster
- **THEN** no notification is sent and the history shows it as suppressed

#### Scenario: A silence expires
- **WHEN** its end time passes
- **THEN** later firings notify normally

### Requirement: Related alerts are deduplicated and grouped
Repeated firings of one alert SHALL notify once, and firings sharing chosen labels within a period SHALL be delivered as one grouped notification.

#### Scenario: Many queues breach together
- **WHEN** forty queues of one cluster fire within the grouping period
- **THEN** one notification lists them

#### Scenario: A firing repeats
- **WHEN** the same alert keeps firing
- **THEN** no additional notification is sent until it resolves or a repeat interval passes

### Requirement: An alert can be escalated until acknowledged
An escalation policy SHALL notify a sequence of targets with delays, and SHALL stop when a user acknowledges the alert. Acknowledgement SHALL record the user and time.

#### Scenario: Nobody acknowledges
- **WHEN** the first target does not acknowledge within its delay
- **THEN** the next target is notified

#### Scenario: Someone acknowledges
- **WHEN** a user acknowledges the alert
- **THEN** escalation stops and the acknowledgement is in the history

### Requirement: Opsgenie is a notification channel kind
The system SHALL support an Opsgenie channel that opens and closes one alert per firing subject, validated and tested like the other kinds, with its secret never exposed after creation.

#### Scenario: A firing opens an Opsgenie alert
- **WHEN** a bound rule fires
- **THEN** an Opsgenie alert is created and closed when the firing resolves

### Requirement: The alert history is searchable
The alert history SHALL be filterable by label, rule, state, time and acknowledgement, and SHALL show routing, silences and escalation steps for each firing.

#### Scenario: An operator investigates
- **WHEN** they filter by cluster and severity for a week
- **THEN** matching firings are listed with how each was routed and who acknowledged it
