# Roadmap

Each phase is one focused development session. The README TODO list is the
same sequence, written as self-contained session briefs. Every feature phase
goes through OpenSpec (`/opsx:propose` → `apply` → `archive`).

## MVP bar

An operator points Artemis Studio at an existing live/backup pair and, within
five minutes, sees something the bundled Hawtio console cannot: the whole cluster
in one view, who is live, replication state, and every queue across every node in
one table.

## Phases

| Phase | Deliverable | Notes |
|---|---|---|
| **0** | Workspace scaffold (done) + broker management spike | Verify the exact Artemis management call signatures (`listNetworkTopology`, `listQueues`, `_AMQ_NotifType`, `Active`, `ReplicaSync`) against the dev compose pair. Make the pair actually boot. |
| **1** | Jolokia client · capability probe · cluster registration · topology discovery · HA + split-brain detection | ADR-0002, ADR-0004 |
| **2** | Cross-node queue/address views · tiered scrape scheduler · per-node rate limiter · SSE hub · React shell + topology graph | ADR-0003 |
| **3** | Message browse / send / move / retry / delete · `?dryRun` · typed confirmation · audit log UI | safety non-negotiables |
| **4** | Core protocol client · `activemq.notifications` consumer · live consumer/session/connection events | unlocks `NOTIFICATIONS` capability |
| **5** | **Request-reply tracing** — correlator, both reply patterns, stuck/orphan detection, per-address latency panel | the flagship |
| **6** | Metrics into partitioned Postgres · Mantine charts dashboards · retention + partition job · frictionless cluster registration · collapsible sidebar | ADR-0006, ADR-0033, ADR-0034 |
| **7** | Alert rules · evaluation loop · webhook / Slack channels | ADR-0035, ADR-0036 |
| **8** | Local users → full RBAC · per-environment grouping · read-only enforcement · OIDC/SSO | |
| **v1.0** | Multi-instance HA (advisory locks) · Helm chart · docs site · slow-consumer detection · message replay | |
| **Beyond** | ArkMQ operator integration · JMX transport · saved views · scheduled reports · broker config diff | ADR-0007 defers these |

## Out of MVP (explicitly)

Request-reply tracing, alerting, metrics charts, multi-instance HA, OIDC, Helm,
JMX transport, ArkMQ integration. Each is a named phase above — not a maybe.
