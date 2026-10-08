## ADDED Requirements

### Requirement: Plugins SHALL send notices through the configured channels

A plugin SHALL be able to list the notification channels by name and kind, without their configuration or secrets, and send a notice to one of them. The notice SHALL be delivered with the same retry, signature and delivery history as alerts. A notice's link SHALL be built by Studio from its public address and a path within Studio. Studio SHALL limit the rate and size of notices per plugin.

#### Scenario: Webhook notice

- **WHEN** a plugin sends a notice to a webhook channel
- **THEN** the webhook receives a signed notice payload with a link into Studio

#### Scenario: Channel secrets

- **WHEN** a plugin lists the channels
- **THEN** it receives no channel configuration or secret
