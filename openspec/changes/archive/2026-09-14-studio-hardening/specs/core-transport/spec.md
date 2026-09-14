## ADDED Requirements

### Requirement: Long-lived capture sessions cannot starve operator operations

Sessions held open indefinitely to drain capture queues SHALL NOT draw on the same bounded
session capacity as operator browses, sends and request-reply sampling. Adding capture taps
to a node SHALL NOT make a browse or send to that node wait or fail.

Every Core consumer and browser SHALL use a bounded client-side prefetch window, so the
memory held for one consumer is bounded.

#### Scenario: Many capture taps do not block a browse

- **WHEN** a node carries more capture taps than a single connection's session limit
- **THEN** an operator browse of a queue on that node is served without waiting for a capture session to be released

### Requirement: A failed Core connection attempt releases its resources

When establishing a Core connection or subscription fails, the system SHALL release every
resource created for the attempt before it retries. Repeated failures against an unreachable
node SHALL NOT accumulate connection factories, threads or descriptors.

#### Scenario: Retrying an unreachable node does not accumulate resources

- **WHEN** a notification subscription to an unreachable node fails and is retried repeatedly with backoff
- **THEN** the resources created for each failed attempt are released, and the live thread count does not grow with the number of attempts
