## ADDED Requirements

### Requirement: Routing is a cross-node resource view like any other

The system SHALL present diverts and bridges through the same cross-node view
mechanism as queues, addresses, consumers, sessions, connections and producers —
the same paging, filtering, node attribution and caching behaviour — rather than as
a separately built screen.

#### Scenario: Routing views behave like the other resource views

- **WHEN** an operator pages, filters or sorts the divert view
- **THEN** it behaves as the other cross-node resource views do, and the state that
  describes what is being viewed is carried in the URL
