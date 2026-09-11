## MODIFIED Requirements

### Requirement: A divert created through the system is disclosed as configuration drift

A divert created over the management API persists on the broker and is absent from the
configuration whatever manages that broker will next deploy. The system SHALL state this
consequence — that the running broker and its configuration will disagree, silently, until
one of them is changed — and SHALL show the configuration that would make them agree.

The configuration shown SHALL be generated from the values the operator supplied, so it can be
applied directly rather than re-entered, and SHALL be copyable in one action.

A divert MAY also be declared in the cluster's declared configuration, in which case the
declaration's drift report is where its presence on every node is tracked.

#### Scenario: The drift consequence is stated before the action

- **WHEN** an operator is about to create a divert
- **THEN** the form states that the divert will persist on the broker and will not appear in
  its configuration, and shows the configuration that would make the two agree

#### Scenario: The configuration is copyable, not retypable

- **WHEN** an operator wants the created divert reflected in configuration
- **THEN** the configuration built from their entered values can be copied in one action
