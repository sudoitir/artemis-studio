## ADDED Requirements

### Requirement: Broker configuration permissions

The system SHALL define a permission for editing a cluster's declared configuration and
a distinct permission for applying it to brokers, each resolvable at global, environment,
or cluster scope through the existing scope walk, and each listed in the permission
catalogue with a human label.

Viewing a declaration, its drift and its history SHALL require only the permission to
view the cluster.

The apply permission SHALL be a create-and-update authority: it covers creating declared
addresses, queues and diverts and adding or replacing address and security settings. It
SHALL NOT grant destroying a queue or an address, which remain governed by the queue
lifecycle permissions.

#### Scenario: Declaring is not applying

- **WHEN** a caller holding the configuration write permission but not the apply permission previews an apply
- **THEN** the request is refused

#### Scenario: Applying never destroys a queue

- **WHEN** a caller holding only the apply permission applies a declaration from which a queue was removed
- **THEN** the queue is untouched
