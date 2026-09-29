## ADDED Requirements

### Requirement: Studio is tested against Artemis HA pairs and clusters
The test suite SHALL run Studio against real Artemis live and backup pairs and multi-node clusters in containers and SHALL assert Studio's behaviour through a failover.

#### Scenario: Failover
- **WHEN** the live broker of a pair is stopped
- **THEN** Studio reports the change of node role, keeps its data consistent and recovers its subscriptions

#### Scenario: Cluster
- **WHEN** a node of a cluster is stopped
- **THEN** the other nodes' views stay correct and the lost node is reported

### Requirement: Failure modes have specified, tested behaviour
For broker kill, network partition and database restart, the expected Studio behaviour SHALL be written down and tested, including what is shown to users and what is not lost.

#### Scenario: Database restart
- **WHEN** the database restarts
- **THEN** Studio degrades, reports it in health, and resumes without a restart

#### Scenario: Partition
- **WHEN** the network between Studio and a broker is cut
- **THEN** the node is reported unreachable, not empty, and recovers when restored

### Requirement: The interface meets WCAG 2.2 AA
The interface SHALL pass an audit against WCAG 2.2 AA, and automated axe checks over its main screens SHALL run in CI and fail on new violations.

#### Scenario: A violation is introduced
- **WHEN** a change adds an unlabeled control
- **THEN** CI fails

#### Scenario: Keyboard use
- **WHEN** a user operates the main workflows with the keyboard only
- **THEN** every action is reachable and focus is visible

### Requirement: Audit findings are fixed or recorded
Every accessibility finding SHALL be fixed, or recorded with a reason and an owner, and none rated serious SHALL remain open at completion.

#### Scenario: Completion
- **WHEN** the change is verified
- **THEN** no serious finding is open

### Requirement: An operations runbook exists and is checked
The documentation SHALL include a runbook for install, upgrade, backup and restore and incident playbooks for the failure modes above, and its commands SHALL be validated against a test environment.

#### Scenario: An incident
- **WHEN** an operator follows a playbook for a lost database
- **THEN** it leads to recovery with commands that were verified
