## MODIFIED Requirements

### Requirement: The console is read-only and every query is audited

The dialect SHALL express no mutation. Acting on a result row SHALL route through
the existing message operations, with their dry run, their bulk cap and their audit
record — the console SHALL NOT provide a second path to a destructive verb.

Running a query SHALL require the message read permission on the target cluster, and
SHALL only search queues within the caller's cluster scope. A query naming a queue
outside that scope SHALL say so rather than returning it as an empty match.

Every executed query SHALL write an audit record carrying the query text, the number
of targets it resolved to, the source that answered, and the number of rows returned.
Literal values in the audited query text that the content policy classifies as
sensitive, or that are compared with a classified field, SHALL be masked before the
record is written. When a query served sensitive values in clear, the record SHALL
also carry the classes and counts served in clear.

#### Scenario: A mutating statement is not expressible

- **WHEN** an operator submits a statement that would delete or move messages
- **THEN** it is rejected at parse time as outside the dialect

#### Scenario: An out-of-scope queue is named, not hidden

- **WHEN** a query's FROM pattern matches a queue the caller has no access to
- **THEN** the response states that a matching queue was excluded by permission, rather than silently omitting it

#### Scenario: A query is audited

- **WHEN** an operator executes a query
- **THEN** an audit record is written with the query text, the resolved target count, the answering source and the row count

#### Scenario: A sensitive literal is masked in the audit record

- **WHEN** an operator runs a query comparing a classified property with a literal email address
- **THEN** the audit record's query text shows the literal masked

## ADDED Requirements

### Requirement: Result rows and exports are governed

Every result row, whether streamed, tailed, or returned in the final result, SHALL carry content governed by the content policy for the caller, with each masked, dropped, withheld or clear-by-grant value identified. An export of results SHALL contain exactly the governed content the caller was shown, and the interface SHALL state that exported content is masked where it was masked on screen.

#### Scenario: A tailed row is governed

- **WHEN** a user without clear access tails a query over messages carrying card numbers
- **THEN** each streamed row shows the card numbers partially masked

#### Scenario: An export carries masked content

- **WHEN** a user without clear access exports results that contained masked values
- **THEN** the exported file contains the masked values, not originals

### Requirement: A predicate cannot reveal a masked value

For a caller without clear access on the target cluster, the system SHALL refuse a query whose predicate references a header or property the content policy classifies for any target address, and the refusal SHALL name the field. Body predicates for such a caller SHALL be evaluated over the governed body.

#### Scenario: A predicate on a classified property is refused

- **WHEN** a user without clear access runs a query with a predicate on property `customerEmail`, which a rule classifies
- **THEN** the query is refused before execution with a reason naming `customerEmail`

#### Scenario: A body search does not match a masked value

- **WHEN** a user without clear access searches bodies for a card number that appears in a message
- **THEN** the message does not match, because the predicate is evaluated over the masked body
