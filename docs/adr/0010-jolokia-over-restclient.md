# ADR-0010: Jolokia calls over a blocking `RestClient`; `spring-boot-starter-webflux` removed

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi

## Context

ADR-0002 makes Jolokia HTTP the primary broker channel. The workspace scaffold
carries `spring-boot-starter-webflux` with a `pom.xml` comment — *"WebClient for
Jolokia HTTP calls; SSE via `Flux<ServerSentEvent>`"* — recording an intent, not
a decision. Phase 1 writes the first real client, so the intent has to become a
decision.

Facts on the ground: `spring.threads.virtual.enabled` is already `true`. The
Jolokia call pattern is one batched POST per node per scrape tier — request /
response, low fan-out, no streaming. Nothing in Phase 1 or Phase 2's scope needs
back-pressure or reactive composition. ADR-0003 puts real-time on SSE, and Spring
MVC serves SSE with `SseEmitter` without WebFlux.

The global engineering rules: *"Do not preserve backward compatibility. Remove
obsolete paths instead of adding compatibility layers, fallbacks, or
migrations,"* and prefer the simplest thing that meets the current requirement.

## Decision

- **The Jolokia client is a blocking `RestClient`** (Spring's synchronous HTTP
  client, already provided by `spring-boot-starter-web`), running on virtual
  threads. A `BrokerClientFactory` builds one `RestClient` per cluster: per-node
  base URL, HTTP Basic from the `SecretVault`, an `SslBundles`-derived request
  factory for TLS brokers, connect timeout 3s, read timeout 10s.
- **`spring-boot-starter-webflux` is removed from `pom.xml`.** Nothing imports
  it; keeping an unused parallel web stack is the obsolete path the rules forbid.
- **SSE (Phase 2) uses `SseEmitter`** on Spring MVC. If a future feature has a
  genuine reactive requirement, WebFlux returns as its own dependency with its
  own ADR.

This supersedes the `pom.xml` "WebClient for Jolokia" comment.

## Consequences

- One HTTP programming model in the codebase; reactive types never enter
  `broker/`, `domain/`, or the Phase 2 scheduler.
- Smaller dependency graph and container image; fewer auto-configurations at boot.
- Blocking I/O is cheap here because it runs on virtual threads and the call
  count per scrape is bounded by node count, not queue count (ADR-0002
  non-negotiable #1).
- A later reactive need pays the cost of re-adding and wiring WebFlux at that
  point — judged unlikely and cleanly reversible.

## Alternatives considered

- **Keep WebFlux, use `WebClient` blocking (`.block()`).** Drags `Mono`/`Flux`
  and a second web stack in for a synchronous call pattern; `.block()` on the
  reactive scheduler is an anti-pattern. Rejected.
- **`java.net.http.HttpClient` directly.** Works, but loses Spring's message
  converters, `MockRestServiceServer` test support, and SSL-bundle integration
  that `RestClient` gets for free. Rejected.
- **Keep the dependency "just in case."** The explicit target of the "no obsolete
  paths" rule. Rejected.
