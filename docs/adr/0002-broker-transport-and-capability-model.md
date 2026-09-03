# ADR-0002: Broker transport is Jolokia-first, capability-gated

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi

## Context

Artemis exposes management three ways: Jolokia HTTP (`/console/jolokia`, on by
default with the web console), a JMS/Core client consuming the management address
and `activemq.notifications`, and raw JMX/RMI. They differ in reach and
capability, not just syntax:

| Channel | Strengths | Weaknesses |
|---|---|---|
| Jolokia HTTP | one port, ingress-friendly, **batched reads in one POST** | request/response only, no push; clumsy for binary bodies |
| Core client | consumes notifications (**push**); faithful message I/O with real headers | verbose for bulk attribute reads |
| JMX/RMI | marginally faster than Jolokia | RMI callback ports hostile to NAT/k8s |

Operators variously have Jolokia only, the Core acceptor only, or control of both.

## Decision

- **Jolokia is the primary channel.** The MVP ships one concrete Jolokia client.
- **The Core client is the second channel**, added in Phase 4 when
  notification-driven features (live events, request-reply tracing, fast
  split-brain detection) arrive. The client interface is extracted then, from two
  real implementations, not guessed up front.
- **JMX is not implemented** until a concrete need appears.
- A `BrokerCapabilities` value, probed at connection time, gates features:
  `MANAGEMENT_READ`, `MANAGEMENT_WRITE`, `NOTIFICATIONS`, `MESSAGE_IO`. The UI
  states *why* a feature is unavailable and shows the exact `broker.xml` snippet
  to enable it — no silently missing controls.
- **HA state is never read from configuration.** Poll `Active` on every node;
  two `true` in a pair raises a critical split-brain alert.
  *Amended by [ADR-0012](0012-corroborated-split-brain.md): the alert now
  requires corroborated evidence (same `NodeID`, same refresh cycle, confirmed
  on the next) so a planned failover does not false-alarm.*

## Consequences

- Works against the most common broker setup with zero broker changes.
- Some flagship features (request-reply tracing) are explicitly unavailable until
  the operator enables the Core acceptor and grants `consume` on
  `activemq.notifications` — surfaced honestly, not hidden.
- Two client implementations to maintain from Phase 4.
- Batched Jolokia POSTs plus tiered scraping (ADR-0006 area) keep a
  thousands-of-queues broker from being overloaded.

## Alternatives considered

- **JMX-first** — best raw fidelity, but the RMI port model makes it unusable
  through the firewalls and k8s networking our users actually run.
- **Core-only** — cleanest single client, but excludes operators who expose only
  the HTTP console, and makes bulk attribute reads expensive.
