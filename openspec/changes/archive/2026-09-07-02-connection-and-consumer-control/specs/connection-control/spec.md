## ADDED Requirements

### Requirement: An operator can close a connection, a session, or an address's consumers

The system SHALL allow an authorized operator to close one client connection, to
close one session within a connection, and to close every consumer connection bound
to an address.

Closing a connection or a session SHALL name the node that issued the identifier,
because a connection identifier is meaningful only on that node. Closing an
address's consumers SHALL name the cluster and SHALL apply to every live node,
reporting the outcome per node.

#### Scenario: A wedged consumer's connection is closed

- **WHEN** an operator closes the connection behind a consumer that is not
  progressing
- **THEN** the connection is closed on the named node and the consumer no longer
  holds its messages

#### Scenario: An address's consumers are closed across the cluster

- **WHEN** an operator closes the consumers bound to an address
- **THEN** the consumers on every live node are closed and the result reports each
  node separately

### Requirement: A close is best-effort about identity and treats a vanished target as success

The system SHALL treat a close whose target no longer exists as successful, because
the requested state — that the connection is not open — holds. It SHALL distinguish,
in the recorded outcome, a connection it closed from one that was already gone.

The system SHALL re-read the target immediately before closing it, and SHALL NOT
rely on the identifier a client supplied from a previously listed view.

The system SHALL NOT retry a close automatically, because the operation is not
idempotent and a retry may act on a different connection that has since been issued
the same identifier.

#### Scenario: Closing a connection that has already gone

- **WHEN** an operator closes a connection that disconnected after it was listed
- **THEN** the result is a success recorded as already gone, not an error

#### Scenario: A stale identifier is not retried onto a new connection

- **WHEN** a close fails
- **THEN** the system reports the failure and does not automatically re-issue it

### Requirement: A close states its consequence for in-flight messages before it is confirmed

Closing a consumer returns the messages it holds to their queue and increases their
delivery count, which may move a message to a dead-letter address.

The system SHALL state this consequence at the point of confirmation, together with
the number of in-flight messages where the broker reports it, so that an operator
does not discover it afterwards from a dead-letter queue.

#### Scenario: The confirmation discloses the message effect

- **WHEN** an operator is asked to confirm closing a consumer holding in-flight
  messages
- **THEN** the confirmation states that those messages return to the queue with an
  increased delivery count, and how many there are

### Requirement: Closing an address's consumers is governed by the bulk safety cap

Because it affects an unbounded number of connections, the system SHALL support
previewing an address-scoped close, reporting the number of consumers that would be
closed on each node without closing any, and SHALL evaluate the total against the
bulk safety cap, refusing it above the cap unless explicitly overridden.

#### Scenario: A preview closes nothing

- **WHEN** an operator previews closing an address's consumers
- **THEN** the per-node counts are reported and no connection is closed

#### Scenario: A large close is refused by default

- **WHEN** an address-scoped close would affect more consumers than the safety cap
- **THEN** it is refused, stating the estimate and the cap, until explicitly
  overridden

### Requirement: The system never selects its own targets to close

The system SHALL close only connections, sessions, or addresses that an operator
has explicitly named. It SHALL NOT offer an operation that selects targets from a
detection heuristic, such as closing every consumer currently judged slow, because
such a detection has false positives by construction and the consequence of acting
on one is a disconnected production application.

#### Scenario: Detection informs, it does not act

- **WHEN** the system identifies a consumer as slow
- **THEN** it presents the finding and the operator may act on it by name, and no
  action closes connections chosen by the detection itself

### Requirement: Closes are permission-gated and audited with a resolvable identity

The system SHALL require a permission distinct from every message permission in
order to close a connection, session, or an address's consumers, resolvable at
global, environment, or cluster scope.

The system SHALL record an audit event capturing the actor, the cluster, the node,
and the identity of what was closed as read immediately before the close — client
identifier, remote address, user, and session and consumer counts — because none of
it is resolvable afterwards.

#### Scenario: A message permission does not grant a close

- **WHEN** a role holds every message permission and not the close permission
- **THEN** its holder cannot close a connection

#### Scenario: The audit record identifies the application, not just an identifier

- **WHEN** a connection is closed
- **THEN** the audit event names the client identifier and remote address that were
  closed, and remains meaningful after the identifier stops resolving
