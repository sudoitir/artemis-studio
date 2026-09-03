# ADR-0007: One container image, Docker Compose first

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi

## Context

The fastest lever on open-source adoption is time-to-first-screenshot. Options
range from a single jar to a Helm chart to an ArkMQ operator integration.

## Decision

- **One container image**: the React SPA is built and baked into the Spring Boot
  jar (`frontend` Maven profile / Dockerfile stage), served from
  `classpath:/static`. The backend owns `/api/**`.
- **Docker Compose first**, in `deploy/compose/`:
  - `compose.dev.yaml` — Postgres + a real Artemis primary/backup pair + Studio.
    `just up` and you see a live topology graph in minutes.
  - `compose.prod.yaml` — Studio + Postgres only (brokers are external and
    registered through the UI), values from `.env`, resource limits, restart
    policy. A reference, not a turnkey production deploy.
- **Helm chart in v1.0.**
- **ArkMQ operator integration (read broker CRs to auto-register clusters) is
  v1.1+**, explicitly out of scope until the core is proven.

## Consequences

- Trivial to evaluate; trivial to run on a VM or small k8s.
- The dev compose pulls the Artemis image and starts a replication pair — heavier
  than a bare jar, but that is the point (you see the product against a real
  cluster).
- Production hardening (TLS termination, managed Postgres) is the operator's job,
  documented, not automated in the MVP.

## Alternatives considered

- **Helm chart first** — raises the bar to try the project; better fit only for
  k8s-only shops.
- **ArkMQ operator integration first** — high effort, narrow audience, premature
  before the core exists.
