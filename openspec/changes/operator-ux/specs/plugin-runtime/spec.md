## ADDED Requirements

### Requirement: A plugin's row actions are namespaced and sectioned, and links stay Studio's

A plugin MAY contribute actions to a resource's row menu. Each such contribution SHALL be
named with the plugin's id as its prefix and SHALL name one of the menu's sections. A
failure inside it SHALL stay inside it.

A plugin SHALL NOT contribute the link that another view uses for a built-in resource. A
plugin bundle that does either of these things SHALL be refused, and the reason SHALL be
stated.

#### Scenario: A plugin tries to own queue links

- **WHEN** a plugin bundle contributes to the queue link slot
- **THEN** it is refused, stating that links to built-in resources are Studio's own
