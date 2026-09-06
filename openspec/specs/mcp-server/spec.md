# mcp-server Specification

## Purpose
Defines the Model Context Protocol surface Studio exposes so an agent can diagnose and tune
a cluster on an operator's behalf — a small set of intent-shaped primitives that inherit the
same identity, permissions, safety and audit guarantees as the human-facing API, rather than
a machine-readable mirror of it.

## Requirements

### Requirement: The surface is a small set of intent-shaped primitives

The system SHALL expose an MCP endpoint whose primitives are shaped by operator intent, not
by the internal API's endpoint list. Operations that share a shape SHALL collapse behind a
discriminator argument rather than becoming separate primitives, and the value set of a
discriminator SHALL be validated by the system rather than enumerated in full in the
primitive's schema.

The surface SHALL declare only the protocol capabilities it implements.

#### Scenario: One primitive covers a family of reads

- **WHEN** a client lists the available primitives
- **THEN** resource kinds that share a listing shape are reachable through a single
  primitive taking a kind argument, not one primitive per kind

#### Scenario: An invalid discriminator value is rejected as a fixable error

- **WHEN** a primitive is invoked with a discriminator value the system does not support
- **THEN** the call fails with a message naming the values that are supported

#### Scenario: Undeclared capabilities are not advertised

- **WHEN** a client reads the server's declared capabilities
- **THEN** only capabilities the system actually implements are declared

### Requirement: The listed surface fits a stated size budget

The system SHALL keep the cost of discovering and using the surface bounded, because a
client pays for the full primitive listing on every connection and for every row of every
result. The published primitive listing SHALL stay within a budget the system enforces
automatically, and no single primitive's schema SHALL exceed a per-primitive share of it.

Result payloads SHALL be projections owned by this surface. They SHALL NOT be the same
representations the human-facing UI consumes, so that the surface's contract cannot drift
by following a UI need.

#### Scenario: The listing budget is enforced, not documented

- **WHEN** the surface's primitive listing grows past the stated budget
- **THEN** the system's own verification fails

#### Scenario: Listing is deterministic

- **WHEN** the primitive listing is requested twice from differently-connected clients
- **THEN** the listing and its ordering are identical

### Requirement: Every list result is capped and ordered

Every primitive returning a list SHALL accept a limit, SHALL apply a low default when none
is given, and SHALL enforce a maximum that a caller cannot exceed. Results SHALL be ordered
deterministically. A result truncated by a cap SHALL say so.

Large payloads SHALL NOT be embedded in a result. A message body SHALL be a second,
explicit fetch rather than inline content of a listing.

#### Scenario: Limit above the maximum is clamped

- **WHEN** a caller requests more rows than the enforced maximum
- **THEN** at most the maximum is returned and the response states it was capped

#### Scenario: Bodies are fetched, not listed

- **WHEN** a page of messages is browsed
- **THEN** headers are returned with an indication that bodies are retrieved separately

### Requirement: MCP calls authenticate as a personal API token and carry that identity

The MCP endpoint SHALL require authentication and SHALL accept the same personal API token
credential as the rest of the API. An unauthenticated call SHALL be refused before any
primitive executes.

An authenticated call SHALL execute under the token owner's identity, and every permission
check, cluster-scope check and audit attribution that applies to the equivalent human-facing
operation SHALL apply identically. The surface SHALL NOT introduce a second credential
store, a second authorization model, or any permission that exists only for MCP.

#### Scenario: Unauthenticated call is refused

- **WHEN** the MCP endpoint is called with no credential
- **THEN** the request is rejected as unauthenticated and no primitive runs

#### Scenario: A token's power is bounded by its owner

- **WHEN** a primitive is invoked with a token whose effective grants do not permit the
  underlying operation
- **THEN** the call fails and no broker or Studio state is changed

#### Scenario: Mutations are audited under the owner with the key attributed

- **WHEN** a mutating primitive completes
- **THEN** an audit event exists attributing the action to the token's owner and naming the
  token used, indistinguishable in coverage from the same action performed in the UI

### Requirement: Mutating primitives dry-run by default and require explicit confirmation

Every mutating primitive SHALL default to a dry run that reports the count that would be
affected and changes nothing. Acting for real SHALL require the caller to both request a
real run and supply a confirmation argument that matches the subject of the operation —
the protocol-level stand-in for the typed confirmation the UI requires and an MCP client
cannot perform.

The confirmation gate SHALL be independent of the bulk-cap override. The two SHALL be
separate arguments, each defaulting to the safe value, and satisfying one SHALL NEVER
satisfy the other.

#### Scenario: Default invocation changes nothing

- **WHEN** a destructive primitive is invoked without arguments beyond its subject
- **THEN** an affected-count estimate is returned, no broker state changes, and an audit
  event is recorded marking the run as a dry run

#### Scenario: A real run without a matching confirmation is refused

- **WHEN** a destructive primitive is asked for a real run with a missing or mismatched
  confirmation argument
- **THEN** the call fails with a message stating what the confirmation must equal, and
  nothing is changed

#### Scenario: Confirmation does not lift the bulk cap

- **WHEN** a confirmed real run would affect more rows than the configured bulk cap and the
  cap override was not requested
- **THEN** the call fails naming the cap and the count, and nothing is changed

### Requirement: Failures are returned as results a model can act on

An operation that fails while executing SHALL be returned as a failed result carrying a
message stating what to do differently, never a raw internal error message or stack trace.
A malformed call, an unknown primitive, or an unknown resource address SHALL be returned as
a protocol-level error rather than an empty successful result.

#### Scenario: A recoverable failure explains the recovery

- **WHEN** a call fails because a limit, a cap, or a confirmation requirement was not met
- **THEN** the failure message names the constraint and the value needed to satisfy it

#### Scenario: Unknown primitive is a protocol error

- **WHEN** a client invokes a primitive that does not exist
- **THEN** the call is rejected as an invalid request, not as a failed result

#### Scenario: Internal detail is never surfaced

- **WHEN** an unexpected internal failure occurs during a call
- **THEN** the returned message contains no stack trace and no raw internal exception text

### Requirement: Denials never reveal what the caller may not see

A denial for a cluster-addressed operation SHALL be indistinguishable from a denial for a
cluster that does not exist, so that a caller cannot use the surface to enumerate cluster
identifiers. Such a denial SHALL NOT name the permission that was missing.

A denial for an operation that is not cluster-addressed has no identifier to protect and
SHALL name the missing permission, so that a caller learns what its key cannot do rather
than retrying blindly.

#### Scenario: No grant on a cluster is indistinguishable from no such cluster

- **WHEN** a cluster-addressed primitive is invoked for a cluster the caller holds no grant on
- **THEN** the failure is worded so that the existence of that cluster is not disclosed, and
  no permission name appears in the message

#### Scenario: A global denial names the permission

- **WHEN** a primitive requiring a global permission is invoked without it
- **THEN** the failure names the permission the key lacks

### Requirement: The surface publishes what the caller can see and do

The system SHALL expose, as read-only context requiring no arguments, the catalogue of
clusters the caller may see, what the caller's own credential is permitted to do, and per
cluster its topology and the broker capabilities that connection can reach.

Where a capability is unavailable because the broker connection lacks it, the published
capability context SHALL say so explicitly and include the exact broker configuration needed
to enable it. A capability SHALL NEVER be silently absent.

#### Scenario: The catalogue is filtered to the caller

- **WHEN** the cluster catalogue is read with a token granted on one cluster
- **THEN** only that cluster appears

#### Scenario: Missing broker capability is stated with its remedy

- **WHEN** capability context is read for a cluster whose connection cannot reach a feature
- **THEN** that feature is reported unavailable together with the broker configuration that
  would enable it

### Requirement: Prompts orchestrate and never act

The system SHALL publish diagnostic runbooks as prompts that sequence the surface's read
primitives. A prompt SHALL be stateless, SHALL NOT perform any operation itself, and a
runbook covering a destructive operation SHALL direct the caller to dry-run and report to a
human rather than to act.

#### Scenario: A destructive runbook stops before acting

- **WHEN** the runbook for a purge is retrieved
- **THEN** it prescribes a dry run, verification of consumer state, and a report to a human,
  and does not prescribe a confirmed run
