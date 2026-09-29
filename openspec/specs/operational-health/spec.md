# operational-health Specification

## Purpose
Defines how Studio reports the health of its own machinery: its background jobs, its connections to broker nodes, and its notification subscriptions. This report stays separate from the probes an orchestrator uses to decide whether to restart Studio.

## Requirements

### Requirement: Every background job reports its status

The system SHALL record, for every scheduled background job of every enabled feature: when it last started, when it last finished, its last error, how many times it has run and failed, and when it is next due. The system SHALL expose this as a read that requires the settings-read permission. Each job's run duration SHALL also be published as a metric labelled with the job and the owning feature.

#### Scenario: A job's last failure is visible

- **WHEN** a background job's most recent run throws
- **THEN** the job status read reports that run's error, its time, and an incremented failure count

#### Scenario: Job status requires permission

- **WHEN** a caller without the settings-read permission requests job status
- **THEN** the request is refused

### Requirement: A stalled job is reported as degraded

The system SHALL report a job as degraded when it has not finished a run within three of its own intervals. The message SHALL name the job and the time of its last completed run.

#### Scenario: A job that stopped running degrades health

- **WHEN** a job with a 15-second interval has not completed a run for 45 seconds
- **THEN** the Studio health report marks that job degraded and names when it last completed

### Requirement: Broker connection and subscription health are reported per node

The system SHALL report, for each registered broker node:
- when a management call last succeeded and last failed;
- how long calls are waiting on the per-node rate limit;
- for the message transport, active and idle connection counts.

It SHALL also report, for each serving node, whether its broker-notification subscription is established, and why when it is not.

#### Scenario: An unreachable node is visible in health

- **WHEN** a registered node stops answering management calls
- **THEN** the health report shows that node's last failure time and cause while other nodes remain reported healthy

#### Scenario: A lost subscription is visible in health

- **WHEN** a serving node's notification subscription drops
- **THEN** the health report shows that node's subscription as not established, with the reason

### Requirement: Studio's operational health never drives a restart

The system SHALL publish jobs, broker connections and subscriptions as a named health group of their own. That group SHALL NOT contribute to the liveness or readiness probes, so a broker outage or a stalled job never causes an orchestrator to restart or unroute Studio.

#### Scenario: A broker outage leaves Studio ready

- **WHEN** every node of a registered cluster is unreachable
- **THEN** the Studio health group reports them degraded, and the liveness and readiness probes still report up

### Requirement: Shutdown releases broker resources in order

On shutdown, the system SHALL:
1. stop accepting and delivering stream events;
2. stop background jobs and scraping, and let no scheduled job start again;
3. write buffered records that are still pending, such as broker events and captured
   messages, and acknowledge on the broker only what was written;
4. close notification subscriptions and capture drains;
5. close message-transport connections;
6. close management clients.

No job SHALL start a broker call once shutdown has begun.

#### Scenario: No broker call starts during shutdown

- **WHEN** shutdown begins while a job is scheduled to fire
- **THEN** the job does not start a new broker call, and subscriptions close before their connections do

#### Scenario: Buffered records are written before shutdown completes

- **WHEN** shutdown begins while broker events or captured messages are buffered but not yet written
- **THEN** they are written before their connections close, and a captured message that could not be written is left unacknowledged on the broker

### Requirement: Resource growth and broker load are observable

The system SHALL expose, on its metrics endpoint:
- its live thread count;
- the number of management requests issued per node;
- the time callers waited for that node's rate-limit capacity, and how often they gave up.

An operator SHALL be able to tell from these alone whether Studio's own resource use is
growing, and whether it is loading a broker at its ceiling.

#### Scenario: Per-node request volume is visible

- **WHEN** Studio issues management requests to a node
- **THEN** the metrics endpoint reports the count of requests issued to that node and the rate-limit wait incurred

#### Scenario: Thread count is visible

- **WHEN** the metrics endpoint is scraped
- **THEN** it reports the live thread count

### Requirement: Installation-wide jobs run once across instances

Every scheduled job SHALL declare its scope: every instance, or once per installation. When several instances share one database, an installation-wide job SHALL run on at most one instance per tick. An instance that finds the job held elsewhere SHALL record the tick as skipped elsewhere, and the job SHALL NOT be reported as degraded for that. If the holder crashes, the job SHALL resume on another instance within one minute, or within one of the job's own intervals when that is longer.

#### Scenario: Two instances, one housekeeping run
- **WHEN** two instances share one database and an installation-wide job is due
- **THEN** it runs on exactly one of them and the other records the tick as skipped elsewhere

#### Scenario: An instance-scoped job runs everywhere
- **WHEN** an instance-scoped job is due on two instances
- **THEN** it runs on both

#### Scenario: A crashed holder does not stall the job
- **WHEN** the instance running an installation-wide job dies mid-run
- **THEN** another instance runs the job once the lock lapses, within one minute or one interval, whichever is longer

### Requirement: Permission declaration mismatches are reported

Studio SHALL compare the permissions that method guards name and that active plugin manifests reference against the permission catalogue, and SHALL report every mismatch in operational health without preventing startup or a plugin's activation. The comparison SHALL cover Studio's own guards from startup and each active plugin's guards and manifest from its activation until its deactivation. A registered permission without a description, and a global-only permission that a guard checks against a cluster, SHALL also be reported. Any mismatch SHALL make the report degraded.

#### Scenario: Guard uses an unregistered permission

- **WHEN** a guard names a permission that is not registered
- **THEN** operational health reports it as a mismatch naming the guarded method, and Studio still starts

#### Scenario: Declared but unregistered

- **WHEN** an active plugin's manifest references a permission that is not registered
- **THEN** operational health reports it naming the plugin

#### Scenario: A deactivated plugin leaves the report

- **WHEN** a plugin whose guards caused a mismatch is deactivated
- **THEN** its mismatches are no longer reported

#### Scenario: Everything matches

- **WHEN** all declared permissions are registered and described
- **THEN** the check reports healthy
