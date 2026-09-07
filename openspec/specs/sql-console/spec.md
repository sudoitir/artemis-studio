# sql-console Specification

## Purpose
Defines the message query console: a restricted SQL dialect an operator writes to
search messages across many queues at once, how that query is planned, costed and
executed against either the live brokers or the historical message index, what a
result row is allowed to claim about itself, and the boundaries — read-only,
bounded, and honest about what it could not see.

## Requirements

### Requirement: Messages can be queried with a restricted SQL dialect

The system SHALL accept a query written in a restricted SQL dialect and return
matching messages. The dialect SHALL support `SELECT`, a single `FROM` naming one
or more queues, `WHERE`, `ORDER BY` and `LIMIT`, over a fixed catalogue of columns
covering message identity, JMS headers, the message body, and application
properties addressed by name.

The dialect SHALL reject, at parse time and before any broker or database is
contacted, anything outside that subset — joins, subqueries, set operations,
common table expressions, any statement that is not a `SELECT`, any function
outside a documented whitelist, and any column not in the catalogue. A rejection
SHALL name the offending token, state why it is not allowed, and where a near
match exists in the catalogue, suggest it.

A `FROM` target SHALL be matched against the queues known for the cluster, and
MAY use the broker's own wildcard syntax to name a set of queues rather than one.
A query naming no queue that exists SHALL say so, distinctly from a query that
matched queues and found no messages.

#### Scenario: A header query returns matching messages

- **WHEN** an operator runs a query selecting messages on a queue with a priority predicate
- **THEN** only messages matching that predicate are returned

#### Scenario: A wildcard names a set of queues

- **WHEN** a query's FROM target uses the broker's wildcard syntax
- **THEN** every queue on the cluster matching that pattern is searched and each returned row names the queue it came from

#### Scenario: A statement outside the subset is rejected before execution

- **WHEN** an operator runs a query containing a join, a subquery, or a statement that is not a SELECT
- **THEN** the query is rejected with the offending construct named, and no broker or database is contacted

#### Scenario: An unknown column suggests a known one

- **WHEN** a query references a column that is not in the catalogue but is close to one that is
- **THEN** the rejection names the unknown column and suggests the catalogue column it resembles

#### Scenario: A FROM target matching nothing is distinguished from an empty result

- **WHEN** a query names a queue pattern that matches no queue on the cluster
- **THEN** the response states that no queue matched, and does not present the outcome as zero matching messages

### Requirement: A query names its source and every result states which source answered

A query SHALL be executable against either the live brokers or the historical
message index, selected by an optional source qualifier on the `FROM` target. When
the qualifier is absent the system SHALL choose the index for a queue that is
indexed and the live brokers otherwise, and SHALL state the choice it made.

Every result set SHALL carry the source that produced it, and every row SHALL carry
the source that produced that row. A row produced from the index SHALL additionally
carry when it was observed and SHALL be presented as possibly no longer present on
the broker.

The system SHALL offer, for any row produced from the index, an action that re-reads
that message from the live broker and reports whether it is still present.

#### Scenario: The source qualifier forces the live brokers

- **WHEN** a query qualifies its FROM target with the broker source
- **THEN** the query is executed against the live brokers even though the queue is indexed, and the result states the broker source answered

#### Scenario: An indexed row is presented as possibly stale

- **WHEN** a result set is produced from the index
- **THEN** each row carries its observation time and the result set states that the messages may have been consumed since

#### Scenario: A consumed message is verified as gone

- **WHEN** an operator verifies an indexed row against the live broker and that message has since been consumed
- **THEN** the verification reports the message as no longer present, and the row is not removed from the result

### Requirement: Predicates the broker can evaluate are pushed down and the rest are scanned

The system SHALL split a query's `WHERE` clause into the part the broker can
evaluate natively — predicates over JMS headers and application properties — and
the residual part it cannot, which includes every predicate over the message body.
The pushed-down part SHALL be evaluated by the broker; the residual part SHALL be
evaluated by the system over the messages the broker returned.

A predicate SHALL only be pushed down when its meaning is identical on both sides.
A predicate whose SQL semantics differ from the broker's selector semantics —
case-insensitive matching among them — SHALL be treated as residual rather than
translated approximately.

A pushed-down predicate SHALL be constructed from the validated query structure and
never by interpolating query text, and the identifiers it names SHALL come from the
column catalogue.

#### Scenario: A header predicate costs no scan

- **WHEN** a query's WHERE clause references only headers and application properties
- **THEN** the broker evaluates the whole predicate and no message is scanned by the system

#### Scenario: A body predicate becomes residual

- **WHEN** a query's WHERE clause references the message body
- **THEN** that predicate is evaluated by the system over the returned messages and the plan reports it as a scan

#### Scenario: A semantically different predicate is not pushed down

- **WHEN** a query uses a case-insensitive comparison that the broker's selector syntax cannot express
- **THEN** the predicate is evaluated as a residual rather than translated, and the plan says so

### Requirement: A query is planned and costed before it executes

The system SHALL expose planning as an operation distinct from execution, which
parses and validates a query, resolves its targets, and returns without contacting a
broker: which source will answer, how many queues and nodes will be read, which
predicates are pushed down, whether a body scan is required, and an estimate of how
many messages will be examined.

A query whose estimated cost exceeds the configured ceiling SHALL be refused before
its first broker call, stating the estimate, the ceiling, and how to narrow the
query. It SHALL NOT be started and silently truncated.

#### Scenario: The plan reports the pushdown split

- **WHEN** an operator plans a query mixing a property predicate and a body predicate
- **THEN** the plan names the pushed-down predicate, names the scanned predicate, and returns without contacting a broker

#### Scenario: An over-budget query is refused, not truncated

- **WHEN** a query's estimated scan exceeds the configured ceiling
- **THEN** the query is refused with the estimate, the ceiling and a narrowing hint, and no broker call is made

### Requirement: Execution is bounded, rate-limited and cancellable

Every broker read a query performs SHALL take a per-node management-call permit
like every other management call, so that a query throttles itself against the
broker rather than the broker against the query.

Execution SHALL be bounded by a configured maximum number of target queues, a
maximum number of messages examined, a maximum number of rows returned, and a wall
clock timeout. A query that reaches any bound SHALL return the rows it has, stating
which bound it reached — never presenting a bounded result as a complete one.

Abandoning a query SHALL stop it: no further broker read is issued once the caller
is gone. A query SHALL NOT outlive the request or stream that asked for it.

The number of queries one operator may run concurrently SHALL be capped.

#### Scenario: A bounded result says it is bounded

- **WHEN** a query examines the configured maximum number of messages before exhausting its targets
- **THEN** the rows found so far are returned together with a statement that the scan cap was reached

#### Scenario: Abandoning a query stops the broker reads

- **WHEN** a client abandons an in-flight query
- **THEN** no further broker read is issued for it

#### Scenario: A query is throttled like any other management call

- **WHEN** a query reads many pages from one node
- **THEN** each read takes that node's management-call permit

### Requirement: A node that does not answer is reported, not omitted

A query fans out across the nodes serving its target queues. When a node fails to
answer, the result SHALL report that node and the reason, and SHALL be presented as
partial. Rows from the nodes that did answer SHALL still be returned.

An absent node SHALL NOT be rendered as an absence of matching messages.

A primary and its synced backup are one logical node; a query SHALL return a
matching message once, not once per endpoint.

#### Scenario: One node fails and the rest answer

- **WHEN** one of three nodes serving a queue pattern does not answer a query
- **THEN** the result contains the rows from the other two, is marked partial, and names the failed node and the reason

#### Scenario: A paired cluster does not double count

- **WHEN** a query targets a queue on a node with a synced backup endpoint
- **THEN** each matching message appears once in the result

### Requirement: A body predicate over truncated content reports an incomplete result

The management channel truncates message bodies at the broker's configured attribute
size limit. When a query evaluates a body predicate over messages read through that
channel, a truncated body can produce a false negative.

The system SHALL detect that a body predicate was evaluated against truncated
content and SHALL mark the result set as possibly incomplete, naming the limit and
the channel that would return full bodies. It SHALL NOT present such a result as
authoritative.

When the same query is served over the channel that returns full bodies, no such
warning SHALL be shown.

#### Scenario: A truncated body warns on a body predicate

- **WHEN** a query with a body predicate is served over the management channel and a returned message's body was truncated
- **THEN** the result is marked possibly incomplete, naming the size limit and the channel that returns full bodies

#### Scenario: A full-fidelity channel does not warn

- **WHEN** the same query is served over the channel that returns untruncated bodies
- **THEN** no incompleteness warning is shown

### Requirement: A result row's identity is node-local

A message identifier is issued by, and meaningful only on, the node holding the
message. A result row SHALL identify a message by the combination of its node, its
queue and its message identifier, and SHALL NOT present the identifier alone as a
cluster-wide identity. Any action taken on a row SHALL carry that full identity.

#### Scenario: A row identifies its node and queue

- **WHEN** a query returns messages from several queues across several nodes
- **THEN** each row names the node and queue it came from alongside its message identifier

### Requirement: Time predicates are expressed in broker time

Message timestamps are assigned by the broker, whose clock may differ from the
system's. A relative time predicate SHALL be resolved against the measured offset
for the node being queried, not against the system clock alone, so that a skewed
broker does not silently return nothing.

Where a target node's clock offset is unknown, the result SHALL state that the time
window could not be normalised for that node.

#### Scenario: A skewed broker still matches a relative window

- **WHEN** a query asks for messages from the last hour against a node whose clock is measurably offset
- **THEN** the window is resolved in that node's time and matching messages are returned

#### Scenario: An unmeasured offset is disclosed

- **WHEN** a relative time predicate targets a node whose clock offset has not been measured
- **THEN** the result states that the window was not normalised for that node

### Requirement: A query can be tailed live, and a tail is a sample

The system SHALL stream new messages matching a query as they are observed, over a
stream scoped to that query, which ends when the client disconnects.

A tail SHALL be implemented without mutating broker configuration and without
consuming messages, and SHALL therefore be presented as a **sample**: messages that
arrive and are consumed between two observations are not seen. The system SHALL
state this for the duration of the tail, and the statement SHALL NOT be dismissable.

Where the system can estimate how many messages passed through unobserved, it SHALL
report that figure rather than implying the tail was complete.

A tail SHALL respect the same per-node rate limit, bounds and cancellation as a
static query.

#### Scenario: New matching messages stream to the client

- **WHEN** a tail is running and a matching message is enqueued
- **THEN** that message is delivered on the query's stream

#### Scenario: The sampling limitation is always stated

- **WHEN** a tail is running
- **THEN** the interface states that messages consumed between observations are not shown, and the operator cannot dismiss that statement

#### Scenario: Disconnecting ends the tail

- **WHEN** a client disconnects from a tail
- **THEN** the tail stops and no further broker read is issued for it

### Requirement: The console is read-only and every query is audited

The dialect SHALL express no mutation. Acting on a result row SHALL route through
the existing message operations, with their dry run, their bulk cap and their audit
record — the console SHALL NOT provide a second path to a destructive verb.

Running a query SHALL require the message read permission on the target cluster, and
SHALL only search queues within the caller's cluster scope. A query naming a queue
outside that scope SHALL say so rather than returning it as an empty match.

Every executed query SHALL write an audit record carrying the query text, the number
of targets it resolved to, the source that answered, and the number of rows returned.

#### Scenario: A mutating statement is not expressible

- **WHEN** an operator submits a statement that would delete or move messages
- **THEN** it is rejected at parse time as outside the dialect

#### Scenario: An out-of-scope queue is named, not hidden

- **WHEN** a query's FROM pattern matches a queue the caller has no access to
- **THEN** the response states that a matching queue was excluded by permission, rather than silently omitting it

#### Scenario: A query is audited

- **WHEN** an operator executes a query
- **THEN** an audit record is written with the query text, the resolved target count, the answering source and the row count

### Requirement: The console teaches its own syntax and its cost model

The console SHALL provide, in the page rather than through an external link, the
column catalogue, the source qualifiers, and worked examples an operator can place
into the editor in one action.

The editor SHALL offer completion over the queues that actually exist on the cluster
and over the column catalogue, and SHALL report a syntax error against the query
being typed without requiring the operator to run it.

The interface SHALL state, before a query runs, whether its predicates are pushed
down to the broker or require a scan, so that the cost of a query is visible at the
moment it can still be changed.

#### Scenario: The syntax reference is reachable from the console

- **WHEN** an operator opens the console's help
- **THEN** the column catalogue, the source qualifiers and runnable examples are shown without leaving the page

#### Scenario: An example is placed into the editor

- **WHEN** an operator chooses a worked example from the help
- **THEN** that query is placed into the editor ready to run

#### Scenario: A syntax error is reported before running

- **WHEN** an operator types a query with an unknown column
- **THEN** the error is reported against the query without the operator running it

#### Scenario: The cost is stated before the query runs

- **WHEN** an operator has typed a query requiring a body scan
- **THEN** the interface states that a scan is required and how many messages it estimates, before the query is run

### Requirement: A query is shareable

The query text, the chosen source and whether the tail is running SHALL be
navigable state, so that a console can be linked to a colleague and restored in the
state it was left. Editor interaction state SHALL remain local.

#### Scenario: A console link restores the query

- **WHEN** an operator copies the console's address while a query is loaded and opens it elsewhere
- **THEN** the same query text and source are restored in the editor
