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
1. report itself not ready, give up its cluster ownership, and wait long enough for the load balancer to
   stop routing to it;
2. tell stream clients to reconnect, stop accepting and delivering stream events;
3. let in-flight bulk runs and transfers finish, or record them interrupted;
4. stop background jobs and scraping, and let no scheduled job start again;
5. write buffered records that are still pending, such as broker events and captured
   messages, and acknowledge on the broker only what was written;
6. close notification subscriptions and capture drains;
7. close message-transport connections;
8. close management clients;
9. record that the replica stopped.

No job SHALL start a broker call once shutdown has begun.

#### Scenario: No broker call starts during shutdown

- **WHEN** shutdown begins while a job is scheduled to fire
- **THEN** the job does not start a new broker call, and subscriptions close before their connections do

#### Scenario: Buffered records are written before shutdown completes

- **WHEN** shutdown begins while broker events or captured messages are buffered but not yet written
- **THEN** they are written before their connections close, and a captured message that could not be written is left unacknowledged on the broker

#### Scenario: Ownership is handed over first

- **WHEN** a replica begins shutting down
- **THEN** its clusters are owned by another live replica within one second

### Requirement: Resource growth and broker load are observable

The system SHALL expose, on its metrics endpoint:
- its live thread count;
- the number of management requests issued per node;
- the time callers waited for that node's rate-limit capacity, and how often they gave up;
- the latency and outcome of management calls and message-transport calls per node;
- how far each background job is behind its schedule;
- the number of connected event stream clients;
- the database connection pool's active, idle, maximum and pending connections.

A node SHALL be identified in metrics by its host and port only, never by a URL that could
carry credentials. An operator SHALL be able to tell from these metrics alone two things: whether
Studio's own resource use is growing, and whether it is loading a broker at its ceiling.

#### Scenario: Per-node request volume is visible

- **WHEN** Studio issues management requests to a node
- **THEN** the metrics endpoint reports the count of requests issued to that node and the rate-limit wait incurred

#### Scenario: Thread count is visible

- **WHEN** the metrics endpoint is scraped
- **THEN** it reports the live thread count

#### Scenario: A node's credentials never reach a metric label

- **WHEN** a node's management URL contains a user name and password
- **THEN** its metrics are labelled with the host and port only

#### Scenario: Job lag is measured

- **WHEN** a job has not completed a run for longer than its interval
- **THEN** the metrics endpoint reports that job's lag as the time past its interval, and zero while it keeps to schedule

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

### Requirement: Studio shows its own health in one view

The system SHALL show, on one screen for holders of the settings-read permission:
- each background job's status and lag;
- each node's last management success and failure, management call latency and rate-limit wait, as seen
  from the replica answering;
- database pool use;
- the event stream client count;
- every replica: its host, version, state, heartbeat age, the clusters it owns, and which one is answering.

Any job or node that is degraded SHALL be marked, and so SHALL the screen as a whole. A replica that is
gone or draining SHALL be marked. The screen SHALL refresh on its own.

#### Scenario: Lag rising

- **WHEN** the poller falls behind
- **THEN** the view shows the lag and marks the job and the view as degraded

#### Scenario: Unavailable figure

- **WHEN** a figure cannot be read
- **THEN** it is shown as unavailable, never as zero

#### Scenario: Without permission

- **WHEN** a user without the settings-read permission requests the health read
- **THEN** the request is refused

#### Scenario: Replicas listed

- **WHEN** two replicas run
- **THEN** the view lists both with the clusters each owns and marks the one answering

### Requirement: Replicas know each other
Every Studio process SHALL register itself as a replica in the shared database with an identity of its
own, its host, its version, its state (starting, ready, draining, stopped) and a heartbeat taken from
database time every five seconds. A replica whose heartbeat is older than fifteen seconds SHALL be
treated as gone. A replica that ends without recording that it stopped SHALL count as a crash; three
crashes within fifteen minutes across the installation SHALL be treated as a crash loop.

#### Scenario: A running replica is not a crash
- **WHEN** a second replica starts while the first keeps running
- **THEN** neither is counted as a crash

#### Scenario: A killed replica is a crash
- **WHEN** a replica is killed without shutting down
- **THEN** after fifteen seconds it is treated as gone and counted as a crash

### Requirement: Replicas share caches and broker work coherently
Cached state SHALL be coherent across replicas. Broker polling, scrapes, subscriptions, sampling and
capture reconciliation SHALL run for each cluster on exactly one live replica, its owner, so that adding
a replica does not multiply the load on a broker and no cluster is left unpolled. Owners SHALL be spread
evenly across live replicas. A replica that cannot confirm its ownership with the database within ten
seconds SHALL stop that cluster's duties. Installation-wide jobs keep running once, as they already do.

Stated times:
- a crashed owner's clusters are taken over within 20 seconds and their duties run again within 25 seconds;
- a replica that shuts down hands its clusters over within one second;
- a replica that joins takes its share within 10 seconds of becoming ready;
- a change to shared state made on one replica is served by every replica within 2 seconds.

#### Scenario: Broker load does not double
- **WHEN** a second replica joins
- **THEN** the management requests per node per interval stay what one replica issued

#### Scenario: One owner per cluster
- **WHEN** two replicas run with several clusters registered
- **THEN** each cluster is owned by exactly one of them, and each replica owns some

#### Scenario: Leader lost
- **WHEN** the owner of a cluster is killed
- **THEN** another replica takes the cluster over within 20 seconds and its duties resume within 25 seconds

#### Scenario: Cache change
- **WHEN** a setting changes on one replica
- **THEN** the other replicas serve the new value within 2 seconds

#### Scenario: Shared safety state
- **WHEN** the owner of a cluster detects a split brain
- **THEN** every replica reports it and refuses the operations it guards

### Requirement: Probes report what an orchestrator needs
Studio SHALL expose liveness at `/livez` and readiness at `/readyz`. Liveness SHALL fail only when the
process cannot recover itself. Readiness SHALL fail while the replica is starting, while it cannot reach
the other replicas for more than ten seconds, and while it is draining. Neither SHALL depend on a broker
being reachable, as the operational health rule already requires. Until migrations have run the probes
SHALL not answer at all, so a startup probe fails.

#### Scenario: Startup
- **WHEN** an instance is still running migrations or joining the stream fan-out
- **THEN** the startup probe fails, and liveness is not evaluated until it passes

#### Scenario: Draining
- **WHEN** shutdown begins
- **THEN** readiness fails first, before any stream or job is stopped

#### Scenario: A broker outage leaves replicas ready
- **WHEN** every broker is unreachable
- **THEN** readiness and liveness still pass

### Requirement: In-flight operations finish or fail cleanly on shutdown
Shutdown SHALL wait up to twenty seconds for in-flight bulk runs and transfers, then SHALL stop them and
record them as interrupted with their progress. A replica that starts SHALL mark as interrupted only runs
whose replica is gone, never a run another live replica is executing. A request to stop a run SHALL
reach it whichever replica receives the request.

#### Scenario: Bulk run at shutdown
- **WHEN** a bulk run is in flight
- **THEN** it completes or is recorded as interrupted with its progress

#### Scenario: A starting replica leaves live runs alone
- **WHEN** a replica starts while another replica is executing a bulk run
- **THEN** the run keeps running

#### Scenario: A crashed replica's run is recovered
- **WHEN** the replica executing a run is killed
- **THEN** within one minute the run is recorded as interrupted with its progress

#### Scenario: Stop from another replica
- **WHEN** a stop request for a run reaches a replica that is not executing it
- **THEN** the executing replica stops the run

### Requirement: A reference HA deployment exists and is tested
The repository SHALL contain a compose template with two replicas, a load balancer that routes on
readiness, and Postgres, documented as the reference deployment, and failover tests SHALL run against it
in continuous integration.

#### Scenario: Kill a replica
- **WHEN** one replica is killed under load
- **THEN** clients continue through the other, the event stream resumes without a gap, and scraping resumes

#### Scenario: Rolling restart without errors
- **WHEN** one replica is stopped gracefully under load
- **THEN** no client request fails and no event is lost
