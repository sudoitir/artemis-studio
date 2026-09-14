## ADDED Requirements

### Requirement: Broker HTTP clients are bounded, shared resources

The system SHALL reuse its broker HTTP clients across calls. The number of clients, and so
the threads and file descriptors they hold, SHALL be bounded by the number of distinct
transport configurations (for example TLS bundles), and SHALL NOT grow with the number of
calls, polls, nodes, stream subscribers or reconnect attempts.

A client whose configuration is replaced by a timeout change, or by a reload of its TLS
material, SHALL be released. Requests already in flight SHALL be allowed to complete. Every
client SHALL be released on application shutdown.

#### Scenario: Repeated polling does not grow the thread count

- **WHEN** the system issues hundreds of broker calls to the same nodes after warming up
- **THEN** the live thread count and open file descriptor count stay flat rather than growing with the number of calls

#### Scenario: A timeout change releases the old client

- **WHEN** an operator changes a broker timeout in Settings
- **THEN** subsequent calls use a client with the new timeouts and the previous client is released

#### Scenario: A reloaded TLS bundle gets a new client

- **WHEN** the TLS material of a bundle used by a cluster is reloaded
- **THEN** the next call to that cluster uses the reloaded material and the client built from the old material is released

#### Scenario: Redirects are still not followed after a timeout change

- **WHEN** a broker timeout has been changed at runtime and a seed URL answers with a redirect
- **THEN** the redirect is surfaced as a connection error rather than followed
