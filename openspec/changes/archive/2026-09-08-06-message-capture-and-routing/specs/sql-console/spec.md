## MODIFIED Requirements

### Requirement: A query can be tailed live, and a tail is a sample

The system SHALL stream new messages matching a query as they are observed, over a
stream scoped to that query, which ends when the client disconnects.

Where the tailed queues are not captured, a tail SHALL be implemented without mutating
broker configuration and without consuming messages, and SHALL therefore be presented as a
**sample**: messages that arrive and are consumed between two observations are not seen. The
system SHALL state this for the duration of the tail, and the statement SHALL NOT be
dismissable.

Where every target of the tail is covered by an active capture subscription, the tail SHALL
be served from the capture and SHALL state that instead — that it shows every message routed
to those addresses while capture has been active — together with the coverage that claim
rests on. The system SHALL NOT present a captured tail and a sampled tail identically, and
SHALL NOT claim capture for a target that is only partly covered.

Where the system can estimate how many messages passed through unobserved — because a poll
missed them, or because a capture dropped them — it SHALL report that figure rather than
implying the tail was complete.

A tail SHALL respect the same per-node rate limit, bounds and cancellation as a static query.

#### Scenario: New matching messages stream to the client

- **WHEN** a tail is running and a matching message is enqueued
- **THEN** that message is delivered on the query's stream

#### Scenario: The sampling limitation is always stated

- **WHEN** a tail is running over queues that are not captured
- **THEN** the interface states that messages consumed between observations are not shown, and the operator cannot dismiss that statement

#### Scenario: A captured tail states the stronger claim

- **WHEN** a tail is running over queues every one of which is covered by an active capture subscription
- **THEN** the interface states that it is showing every message routed to those addresses while capture has been active, rather than the sampling statement

#### Scenario: Partial capture does not claim capture

- **WHEN** a tail's targets include one covered by capture and one that is not
- **THEN** the interface states the sampling limitation and names the target it cannot claim capture for

#### Scenario: A message consumed immediately is tailed when captured

- **WHEN** a message is routed to a captured address and consumed before any poll could observe it
- **THEN** it is delivered on the tail's stream

#### Scenario: Disconnecting ends the tail

- **WHEN** a client disconnects from a tail
- **THEN** the tail stops and no further broker read is issued for it

## ADDED Requirements

### Requirement: Message bodies can be searched by word and phrase, with relevance ordering

The dialect SHALL provide a full-text predicate over the message body supporting whole
words, quoted phrases, and the exclusion of a term, and SHALL allow results to be ordered by
how well they match it.

The predicate SHALL be evaluated by the stored index and SHALL NOT be offered against a live
broker, which has no such facility. A query using it against a live broker SHALL be refused
with that reason, in the same way as any other index-only column.

Relevance ordering SHALL be available only when the query contains the full-text predicate,
and SHALL be refused with its reason otherwise.

#### Scenario: A phrase search matches only the phrase

- **WHEN** an operator searches for a quoted phrase
- **THEN** only messages whose body contains that sequence of words are returned

#### Scenario: An excluded term removes matches

- **WHEN** an operator searches for a term while excluding another
- **THEN** messages containing the excluded term are not returned

#### Scenario: Full-text against a live broker is refused with its reason

- **WHEN** a query uses the full-text predicate against the broker source
- **THEN** it is refused, naming the predicate and stating that it is answerable only from the stored index

#### Scenario: Relevance ordering without a full-text predicate is refused

- **WHEN** a query orders by relevance without a full-text predicate
- **THEN** it is refused with that reason

### Requirement: Query text is not carried in a URL

The system SHALL NOT transmit query text as part of a request URL. A query is submitted in a
request body and the resulting stream is opened by reference to it.

Query text can contain the values an operator is searching for, which are application data;
a URL is recorded by intermediaries that a request body is not. A query SHALL also not be
limited in length by what a URL can carry.

#### Scenario: Query text is not in the request URL

- **WHEN** an operator runs any query, static or tailed
- **THEN** no request URL contains the query text

#### Scenario: A long query runs

- **WHEN** an operator runs a query longer than a URL can carry
- **THEN** it executes normally

### Requirement: A running query can be stopped from the interface

While a query is executing, the interface SHALL offer a control that stops it, for a static
query as well as for a tail. An operator SHALL NOT have to navigate away to stop a query
that is fanning out across a cluster.

#### Scenario: A static query can be stopped

- **WHEN** a static query is executing
- **THEN** a control that stops it is available, and using it stops the broker reads

### Requirement: A result states its provenance per row and its coverage per node

Where a result can contain rows from more than one source, each row SHALL state which source
it came from, and a captured row SHALL be distinguishable from a sampled one — the first
records everything routed to an address while capture was active, the second records only
what happened to be observed.

Coverage SHALL be reported per node. A result whose targets are captured on some nodes and
not others SHALL name the nodes it cannot answer for, rather than presenting the rows it has
as the answer.

#### Scenario: Captured and sampled rows are distinguishable

- **WHEN** a result contains both captured and sampled rows
- **THEN** each row states which it is

#### Scenario: Partial node coverage is named

- **WHEN** a query's targets are captured on one node and not on another
- **THEN** the result names the node it cannot answer for

### Requirement: The console retains what was run and can export what was returned

The system SHALL retain the queries an operator has run in this browser, with when each ran,
how many rows it returned, and which source answered, and SHALL allow one to be reloaded
into the editor.

The system SHALL allow the rows of a result to be exported in a machine-readable form.

An operator mid-incident re-runs variations of the same query, and retyping one from memory
is how the wrong query gets run and believed.

#### Scenario: A previous query can be reloaded

- **WHEN** an operator selects a previously run query from the history
- **THEN** its text is loaded into the editor without being executed

#### Scenario: A result can be exported

- **WHEN** an operator exports a result
- **THEN** the rows are produced in a machine-readable form

### Requirement: A tail can be paused without being stopped

While a tail is running, the interface SHALL allow it to be paused. While paused the tail
SHALL continue to receive rows and SHALL state how many have arrived, and resuming SHALL
present them without having lost any.

The interface SHALL NOT move the operator's position in the results while they are reading
rows above the newest.

#### Scenario: Pausing holds the view and counts what arrives

- **WHEN** an operator pauses a running tail and matching messages continue to arrive
- **THEN** the displayed rows do not change and the number of rows waiting is stated

#### Scenario: Resuming loses nothing

- **WHEN** an operator resumes a paused tail
- **THEN** the rows that arrived while it was paused are shown
