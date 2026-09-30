## MODIFIED Requirements

### Requirement: Every store has one retention policy in one place
Studio SHALL expose a retention policy for every store that grows with use: metrics, message index,
captured payloads, broker events, request-reply flows, audit, bulk runs, transfer runs, alert history,
broker configuration history, setup reviews, classification findings, expired sessions, API token usage and storage
samples. Each policy SHALL have a default and an enforced minimum and maximum, and SHALL be editable on
the Data page by a user holding `data:write`. A change SHALL be audited and SHALL apply at the next
purge, without a restart. These policies SHALL NOT also be listed on the Settings page.

#### Scenario: Set a policy
- **WHEN** an administrator sets a store's retention within its bounds
- **THEN** the policy is saved, an `UPDATE_SETTING` audit event records the old and new values, and the next purge uses it

#### Scenario: Out of bounds
- **WHEN** a retention outside the store's bounds is submitted
- **THEN** it is rejected with a message naming the allowed range, and nothing is saved or audited

#### Scenario: Forever only where allowed
- **WHEN** `forever` is submitted for a store whose bounds do not allow it
- **THEN** it is rejected with the allowed range

#### Scenario: Writing needs the data permission
- **WHEN** a user without `data:write` changes a policy
- **THEN** the request is refused with 403

#### Scenario: No unbounded store
- **WHEN** the tables created by the changelogs are compared with the tables the stores name
- **THEN** every table either belongs to a store or is listed as bounded by design with a reason, and a build test fails for a new table that is neither

#### Scenario: Message-index subscriptions are capped by the store
- **WHEN** a subscription's retention is set longer than the message-index store's retention
- **THEN** it is rejected with the store's retention as the maximum

