# approval-gate Specification

## Purpose
Gate destructive, bulk, settings and access-control operations behind an optional approval provider: hold, decide, and replay a held operation once, exactly as requested, after a different person approves it.

## Requirements

### Requirement: Studio SHALL let an installed provider hold an operation for approval

For a defined set of destructive and bulk operations, Studio SHALL ask an installed approval provider whether the operation may run now, whatever the entry point: the UI, the API, the CLI or an MCP tool. The provider MAY answer allow, hold or deny. On hold, Studio SHALL NOT run the operation, and SHALL tell the requester it is waiting. Studio SHALL tell the provider how the requester authenticated (interactive session, API token or agent).

#### Scenario: Held operation

- **WHEN** a user starts a purge and the provider answers hold
- **THEN** nothing is purged and the user sees the operation is pending approval

#### Scenario: Denied

- **WHEN** the provider answers deny
- **THEN** the operation is refused with the provider's reason

#### Scenario: Same operation through another entry point

- **WHEN** an operation that is held in the UI is started through the API or an MCP tool
- **THEN** it is held in the same way

### Requirement: Studio SHALL behave unchanged when no provider is installed

With no approval provider, all operations SHALL run as they do today, with no extra step, message or delay.

#### Scenario: No provider

- **WHEN** a user runs a bulk delete with no provider installed
- **THEN** it runs immediately

### Requirement: A held operation SHALL run only as originally requested, and only once

When the provider approves, Studio SHALL run exactly the request that was held. The parameters SHALL NOT change between request and approval, and the operation SHALL run at most once. The approver's and requester's identities SHALL be available to the audit. Studio SHALL give the provider the operation's expected effect (such as the number of messages) when it is held and again just before it runs, and SHALL NOT run it if the provider refuses at that point.

#### Scenario: Approved

- **WHEN** the provider approves a held operation
- **THEN** it runs once with the original parameters

#### Scenario: Replay

- **WHEN** an approval is presented a second time
- **THEN** the operation does not run again

#### Scenario: Tampered request

- **WHEN** the held request is edited after the request was made
- **THEN** the approval no longer applies

#### Scenario: Effect changed before running

- **WHEN** the provider refuses an approved operation because its effect at run time differs from what was approved
- **THEN** the operation does not run and the requester sees why

### Requirement: Permissions SHALL still apply when an approved operation runs

The approved operation SHALL run only if the requester still holds the permission needed, and it SHALL run in the requester's scope.

#### Scenario: Permission revoked

- **WHEN** the requester loses the permission before the approved operation runs
- **THEN** the approved operation is refused

### Requirement: A held operation SHALL be able to be cancelled or expire

The requester SHALL be able to cancel a held operation. A provider or Studio SHALL be able to expire it. A cancelled or expired operation SHALL NOT run.

#### Scenario: Cancelled

- **WHEN** the requester cancels a held operation
- **THEN** it never runs

### Requirement: Removing the provider SHALL NOT release held operations

Deactivating or removing the approval provider SHALL be audited and SHALL cancel every held operation; none of them SHALL run.

#### Scenario: Provider deactivated with requests pending

- **WHEN** an admin deactivates the provider while operations are held
- **THEN** the held operations are cancelled, their requesters are told, and the deactivation is audited

### Requirement: The gate SHALL fail closed for held operation types

If a provider is installed but does not answer within a bound, Studio SHALL NOT run the operation and SHALL report why.

#### Scenario: Provider unreachable

- **WHEN** the provider errors or times out
- **THEN** the operation is not run and the requester sees a clear error

### Requirement: The gate SHALL be generic

The contract and UI states SHALL NOT refer to a specific product.

#### Scenario: Third-party provider

- **WHEN** a third-party plugin implements the provider
- **THEN** it works with no Studio change

### Requirement: The gate SHALL cover destructive, settings and access-control operations

Studio SHALL pass every one of these operations through the gate, whatever the entry point:

- purges;
- message delete, move, retry and expire;
- bulk runs;
- queue and address deletes;
- transfers;
- settings changes and key rotation;
- user, role, team, group-mapping and default-role changes;
- API token creation and revocation by an administrator;
- plugin install, enable, disable, rollback, uninstall, licence, installer, trust-key and trust-policy changes;
- environment and cluster changes.

Dry runs SHALL NOT be gated. A test SHALL fail when a gated operation is reachable without passing the gate.

#### Scenario: A settings change is held

- **WHEN** a team member changes a setting and the provider answers hold
- **THEN** the setting keeps its value and the team member sees the change is waiting for approval

#### Scenario: An access change is held

- **WHEN** an administrator grants a role and the provider answers hold
- **THEN** the role is not granted until the request is approved

#### Scenario: Dry run

- **WHEN** an operator previews a purge as a dry run
- **THEN** the provider is not asked and the preview is shown

#### Scenario: Items of an approved bulk run

- **WHEN** an approved bulk delete runs over many queues
- **THEN** each queue is processed without a further approval

### Requirement: Only a different person in a fresh interactive session SHALL decide

Studio SHALL accept an approval or rejection only under all of these conditions:

- it comes from an interactive session whose authentication is fresher than the re-authentication window;
- it echoes the exact request it was shown;
- it comes from a user other than the requester;
- the user holds the provider's approver permission for the operation's scope at that moment;
- the user's account existed before the request was made;
- the user is not linked to the requester through the same external identity or email.

An approval SHALL be refused if the requester changed anyone else's access after making the request. A rejection SHALL carry a reason. Every refused decision SHALL be audited.

#### Scenario: Self approval

- **WHEN** the requester tries to approve their own request
- **THEN** it is refused and audited

#### Scenario: Approval with an API token or by an agent

- **WHEN** an API token or an MCP agent tries to decide a request
- **THEN** it is refused and audited

#### Scenario: Requester grants access after requesting

- **WHEN** the requester grants any user a role, team or mapping after making the request, and a user then approves it
- **THEN** the approval is refused and audited

#### Scenario: Two approvers at once

- **WHEN** two approvers decide the same request at the same moment
- **THEN** one decision counts and the other is told the request was already decided

#### Scenario: The request changed while on screen

- **WHEN** an approver approves a request whose parameters differ from what their screen showed
- **THEN** the approval is refused

### Requirement: A held request SHALL be bound to its exact parameters and survive tampering checks

Studio SHALL bind a held request's parameters, requester and policy with a keyed seal held outside the database. It SHALL refuse to run a request whose stored parameters, decision or seal do not match. A request that held a secret SHALL NOT keep it once the request is finished.

#### Scenario: Stored parameters edited in the database

- **WHEN** a held request's parameters are changed directly in the database and it is then approved
- **THEN** it does not run, and the refusal is audited

### Requirement: An approved operation whose outcome is unknown SHALL NOT run again

If Studio stops while running an approved operation, it SHALL mark the request's outcome as unknown and SHALL NOT run it again.

#### Scenario: Replica stops mid-run

- **WHEN** the replica running an approved purge stops before recording the outcome
- **THEN** the request is marked outcome unknown and is not run again by any replica

### Requirement: An operation whose result is secret SHALL be completed by its requester

For an operation whose result only its requester may see, such as a new API token, approval SHALL let the requester complete it themselves within a set time, and nobody else.

#### Scenario: Approved token creation

- **WHEN** a token creation is approved and the requester submits it again within the time allowed
- **THEN** the token is created and its secret is shown only to the requester

### Requirement: Break-glass SHALL be a loud, deployment-level switch

Studio SHALL let gated operations bypass the provider only when a break-glass reason is set in the process environment or system properties, never from the database or the UI. While it is set, every bypassed operation SHALL be audited, every user SHALL see a banner, approvers SHALL be notified, and held requests SHALL stay held.

#### Scenario: Break-glass from the database

- **WHEN** the break-glass property is supplied through database-backed configuration
- **THEN** Studio refuses to start and says why

#### Scenario: Break-glass on

- **WHEN** break-glass is set in the environment and an operator purges a queue
- **THEN** the purge runs, the bypass is audited, and the banner is shown

### Requirement: Requesters SHALL be told the outcome

The requester SHALL be notified in Studio when their request is approved, rejected, expired, cancelled, refused at run time, succeeded or failed. The requester SHALL see the request with its timeline.

#### Scenario: Rejected

- **WHEN** an approver rejects a request with a reason
- **THEN** the requester gets an inbox item with the reason, linked to the request
