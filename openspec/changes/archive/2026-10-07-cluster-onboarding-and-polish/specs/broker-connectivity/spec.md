# Spec Delta: broker-connectivity

## ADDED Requirements

### Requirement: A rejected credential is named for its account

When a broker rejects the management account (HTTP 401 or 403 from Jolokia) or the Core account
(a Core security exception), the cluster's health SHALL name which account was rejected, on
which nodes, and SHALL link to where that account is edited. A rejection SHALL NOT be reported
as an unreachable broker. Credentials changed on the brokers outside the system SHALL therefore
surface as this state within one scrape, and SHALL clear on the next successful call after they
are updated.

#### Scenario: Password changed outside Studio
- **WHEN** the management password is changed on the brokers and the next scrape runs
- **THEN** the cluster's health says the management account was rejected on every node and links to the Connection settings

#### Scenario: Only the Core account is rejected
- **WHEN** Jolokia calls succeed and Core connections are refused for bad credentials
- **THEN** health says the Core account was rejected, and management features keep working
