## ADDED Requirements

### Requirement: Results can be filtered and transformed by processors
The console SHALL support processors that filter and transform rows of a result, and the result SHALL state which processors ran.

#### Scenario: A transform
- **WHEN** a query projects a header as a column
- **THEN** the result carries the derived column and names the processor

#### Scenario: A write is attempted
- **WHEN** a processor tries to change a message
- **THEN** it is refused and the console stays read-only

### Requirement: Results can be aggregated
The console SHALL support group-by aggregations and windowed counts over message properties and broker time.

#### Scenario: Group by
- **WHEN** a query groups by a header and counts
- **THEN** one row per value is returned with its count

#### Scenario: Windowed count
- **WHEN** a query counts per minute
- **THEN** one row per window is returned in broker time

### Requirement: Aggregations respect masking
An aggregation or group-by SHALL NOT reveal a masked value, directly or through its grouping keys.

#### Scenario: Grouping by a masked field
- **WHEN** a user groups by a masked header
- **THEN** the keys are masked or the query is refused

### Requirement: Joins are supported only under a proven safe model
Joins SHALL be offered only if a model is proven that bounds their cost on the broker and Studio; otherwise the rejection and its reasons SHALL be documented and the console SHALL refuse joins with a clear message.

#### Scenario: Join not supported
- **WHEN** a user submits a join
- **THEN** the console refuses it and points to the documented reason

#### Scenario: Join supported
- **WHEN** a safe model is adopted
- **THEN** every join is planned, costed and bounded like other queries

### Requirement: Processors run within resource guards
Every processor SHALL run within limits on time, rows and memory, and a query that exceeds one SHALL stop and say which.

#### Scenario: Memory limit
- **WHEN** an aggregation exceeds its memory limit
- **THEN** it stops and reports an incomplete result naming the limit

#### Scenario: Row limit
- **WHEN** a query exceeds the row limit
- **THEN** it stops and marks the result truncated

### Requirement: A query shows an explain plan
The console SHALL show, before running and on request, the plan for a query including processors, what is pushed to the broker, what is scanned and the estimated cost.

#### Scenario: Explain
- **WHEN** a user requests the plan
- **THEN** the plan lists each stage and its estimated cost without executing the query
