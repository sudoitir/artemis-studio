## ADDED Requirements

### Requirement: Captured messages are stored governed

A captured message SHALL be governed by the content policy before it is stored, exactly as a sampled message is: sensitive values masked, credentials dropped, uninspectable content withheld, and non-credential originals sealed.

The interface that arms capture SHALL state that sensitive values are stored masked.

Governing a captured batch SHALL NOT slow the drain enough to block a producer. When governing cannot keep pace, the loss SHALL be measured and reported like any other capture loss.

#### Scenario: A captured credential is never stored

- **WHEN** capture stores a message carrying an `Authorization` property
- **THEN** the stored row holds a dropped-credential marker and no sealed original for that property

#### Scenario: Capture states what it stores

- **WHEN** an operator arms capture for a queue
- **THEN** the interface states that complete message bodies are stored for the retention period, with sensitive values masked
