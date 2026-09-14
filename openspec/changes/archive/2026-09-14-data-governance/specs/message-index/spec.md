## MODIFIED Requirements

### Requirement: Indexed payload is treated as retained data

The index holds message bodies, which are application payload. The system SHALL
require the message read permission to query the index, scoped to the caller's
clusters exactly as a live query is.

The index SHALL store each message in the form the content policy produces:
sensitive values masked, credentials dropped, uninspectable content withheld. Word,
phrase and pattern search SHALL operate only over that stored form. Originals of
masked values other than credentials SHALL be stored sealed with the row and
revealed only to a caller holding clear access for the row's cluster. Each row
SHALL record the policy version it was stored under.

The interface that creates a subscription SHALL state, before it is confirmed, that
message bodies will be stored by Studio for the chosen retention period, and that
sensitive values are stored masked.

#### Scenario: The operator is told what is being stored

- **WHEN** an operator creates an index subscription
- **THEN** the interface states that message bodies will be stored for the chosen retention period, with sensitive values masked, before the subscription is created

#### Scenario: Index reads are permission-scoped

- **WHEN** an operator without message read permission on a cluster queries the index for that cluster
- **THEN** the query is refused

#### Scenario: A sensitive value is not stored in clear

- **WHEN** a message whose property holds a valid card number is indexed
- **THEN** the stored property holds the masked form and the stored row carries a sealed original and its policy version

#### Scenario: A predicate on a masked field warns about the stored form

- **WHEN** an operator with clear access queries the index with an equality predicate on a field that is masked when stored
- **THEN** the result warns that the index holds masked values for that field and that the broker source can evaluate the predicate against originals
