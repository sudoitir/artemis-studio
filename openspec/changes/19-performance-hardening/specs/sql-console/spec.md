## ADDED Requirements

### Requirement: Heavy queries run within a time and cost budget
The console SHALL enforce a maximum execution time and a maximum planned cost per query, refuse a query whose planned cost exceeds the budget, and stop one that exceeds its time budget, saying which limit applied.

#### Scenario: A query is too costly
- **WHEN** the plan exceeds the cost budget
- **THEN** the query is refused before it runs, with the estimated cost and the limit

#### Scenario: A query runs too long
- **WHEN** a running query exceeds the time budget
- **THEN** it is stopped and the partial result is marked incomplete

### Requirement: A user's heavy operations are limited in concurrency
Studio SHALL limit how many heavy operations one user can run at once, and SHALL answer an excess request with a clear refusal that names the limit.

#### Scenario: A user opens too many queries
- **WHEN** a user starts more heavy queries than the limit
- **THEN** the extra ones are refused with a message naming the limit and are not queued silently

#### Scenario: Another user is unaffected
- **WHEN** one user reaches the limit
- **THEN** other users still run their queries
