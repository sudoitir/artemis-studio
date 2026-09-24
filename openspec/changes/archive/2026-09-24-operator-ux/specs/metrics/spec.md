## ADDED Requirements

### Requirement: A queue's series can be broken down per broker node

A metric query for one queue SHALL accept a request to split it by node. The response
SHALL:
- keep the total series unchanged;
- add one entry per serving node, named, with the same metrics and buckets.

Each node's rate SHALL be computed the same way as the total, so that the nodes add up to
it. A serving node with no sample in the window SHALL be listed as not sampled, never as
zero.

A split SHALL be refused for any scope other than one queue. It SHALL be bounded:
- at most 16 nodes;
- a step widened until nodes times buckets stays within 2,000.

When a bound applies, the response SHALL say so.

The metrics view SHALL offer the split for a queue, drawing one chart per node on a shared
scale.

#### Scenario: Nodes add up to the total

- **WHEN** a queue receives 30 msg/s on one node and 10 msg/s on another, and its series
  is split by node
- **THEN** the node series read 30 and 10 and the total reads 40

#### Scenario: An unsampled node

- **WHEN** a serving node has no sample for the queue in the window
- **THEN** it is listed as not sampled

#### Scenario: Cluster scope is refused

- **WHEN** a split is requested for the cluster scope
- **THEN** the request is refused, stating that a split needs one queue
