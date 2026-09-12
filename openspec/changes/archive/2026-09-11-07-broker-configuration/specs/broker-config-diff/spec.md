## ADDED Requirements

### Requirement: The comparison links to the declaration and states how the two differ

The node-to-node comparison SHALL link to the cluster's declared configuration and its
drift report, and the drift report SHALL link back, each stating in one sentence how
they differ: the comparison sets two nodes against each other; drift sets every live
node against the declaration. Both SHALL read a node's effective configuration through
the same reader, so that they cannot report different truths about one node.

#### Scenario: The two views name their difference

- **WHEN** an operator opens the node comparison
- **THEN** it links to the drift report and states that drift compares against the declaration rather than against another node
