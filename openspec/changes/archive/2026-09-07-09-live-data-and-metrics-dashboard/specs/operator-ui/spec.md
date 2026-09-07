## ADDED Requirements

### Requirement: A view whose size grows with the cluster is bounded

The system SHALL bound what any view renders at once, so that the amount of work a
screen does is a function of what the operator can see rather than of how large the
cluster is. A view listing rows SHALL either present a bounded page of them with a
way to reach the rest, or render only the rows within the viewport, or both.

A view that is bounded SHALL make the bound visible: an operator SHALL NOT be left
unable to tell whether they are looking at everything.

#### Scenario: A large result does not render in full

- **WHEN** a view's underlying result contains far more rows than fit on a screen
- **THEN** the number of rows rendered is bounded, and the view states that more
  exist and how to reach them

#### Scenario: A single page is not silently the whole answer

- **WHEN** a view fetches a bounded page of results and more pages exist
- **THEN** the view offers a way to reach the following pages

#### Scenario: A dense graph degrades rather than drawing everything

- **WHEN** a topology contains more nodes than can be drawn legibly at once
- **THEN** the view reduces the detail it draws rather than drawing every node at
  full detail, and states that it has done so
