## Context

See `proposal.md` — Why. The approved implementation plan is
`/home/sudoit/.claude/plans/phase-1-sleepy-harp.md`; this document is its
distilled, spec-aligned form.

Current state: `io.github.sudoitir.artemisstudio` has only
`ArtemisStudioApplication`, `Branding`, and `config/SecurityConfig`
(`permitAll()`). No controllers, services, repositories, or DTOs. Schema
changesets 001–007 are released; `002-estate.sql` already defines `cluster`,
`broker_node`, `broker_credential`, `broker_tls`. `openspec/specs/` is empty —
this is the repo's first change. Phase 0 output
(`docs/broker-management-notes.md`) is the authoritative record of Jolokia
request/response shapes and HA semantics.

Binding constraints: ADR-0002 (Jolokia-first, capability-gated), ADR-0004 (seed
+ discovery), ADR-0008 (Liquibase, never edit a released changeset), the ten
non-negotiables in `CLAUDE.md`, and the global engineering rules (no
backward-compat layers, simplest thing that works, reuse before adding).

## Goals / Non-Goals

**Goals:**

- One concrete Jolokia client, batched, one POST per node.
- A capability probe that never mutates the broker and always explains a gap.
- Credential encryption at rest with a JDK primitive, no new dependency.
- Registration from a seed list, topology discovery keyed on `NodeID`, manual
  overrides that survive rediscovery.
- Corroborated split-brain detection that does not false-alarm on planned failover.
- A working browser view: register, see the pair, read the capability ledger.
- Leave Phase 2 (tiered scheduler, rate limiter, SSE hub, router) clean seams.

**Non-Goals:**

- Authentication / RBAC (Phase 8). Only a startup exposure warning here.
- The Core protocol client and live notifications (Phase 4). `NOTIFICATIONS`
  stays `UNKNOWN`.
- Queue/address/consumer views and the scrape tiers (Phase 2).
- TanStack Router wiring (Phase 2) — the frontend stays routerless.
- Reactive/streaming HTTP. `RestClient` on virtual threads is the whole story.

## Decisions

### D1 — Jolokia over blocking `RestClient` on virtual threads (ADR-0010)

`spring.threads.virtual.enabled` is already `true`. A blocking client keeps
reactive types out of `domain/` and out of Phase 2's scheduler. The alternative,
`WebClient`, was the pre-existing intent (a `pom.xml` comment only) and would
drag `Mono`/`Flux` through the call chain for no benefit at this concurrency.
**`spring-boot-starter-webflux` is removed** — nothing uses it, and an unused
parallel web stack is exactly the "obsolete path" the first engineering rule
forbids. Phase 2's SSE uses MVC `SseEmitter`.

One `BrokerClientFactory` builds a per-cluster `RestClient` (base URL per node,
basic-auth from the vault, `SslBundles` when `broker_tls` names one), connect
timeout 3s, read timeout 10s on the request factory.

### D2 — `search` to resolve the broker MBean

The broker MBean segment is `broker="<name>"` from `broker.xml`, unknown at
registration. A Jolokia `search` for `org.apache.activemq.artemis:broker=*`
(no trailing `,*`, so sub-components are excluded) returns the one top-level
object name; cache it per node. Verified against the Jolokia protocol docs.

### D3 — Batched POST parsing

`JolokiaBrokerClient.batch(List<JolokiaRequest>)` → `List<JolokiaResponse>`,
positionally aligned. Each `JolokiaResponse` exposes its own `status`, `value`,
and `error`/`errorType`. Callers check per-entry status; a 404 entry never
fails the batch. `listNetworkTopology()` and `listQueues()` `value` is a
JSON string — a small helper double-parses it. Multi-attribute reads use
`attribute: [...]` (array) in the POST body.

### D4 — `SecretVault` from the JDK, not a library (ADR-0009)

`javax.crypto.Cipher` with `AES/GCM/NoPadding`, `SecureRandom` 12-byte nonce,
128-bit tag. Root key from `ARTEMIS_STUDIO_SECRET_KEY` decoded as base64 →
must be 32 bytes or the bean fails to construct (fail fast at startup). AAD =
`clusterId.toString() + "|" + kind`, so a ciphertext row is cryptographically
bound to its `(cluster_id, kind)` and cannot be relocated. Nonce goes in the
existing `secret_nonce BYTEA` column; ciphertext+tag in `secret_ct BYTEA`.
No new dependency; a library here would be pure ceremony.

### D5 — TLS by SSL bundle name

`broker_tls.truststore_ref` holds a Spring SSL **bundle name**, resolved via
the auto-configured `SslBundles` bean, applied to the `RestClient` request
factory. `verify_hostname` maps to the bundle's options. The schema has no
truststore-password column; inventing one to pass a file path is worse than
using the framework's own mechanism.

### D6 — `JdbcClient`, not Spring Data JDBC (ADR-0011)

`architecture.md` says "Spring Data JDBC", but its aggregate `save` deletes and
reinserts child rows, which would reassign `broker_node` UUIDs on every refresh
tick and break `audit_event.node_id` FKs. Small hand-written repositories over
`JdbcClient` (same `spring-boot-starter-data-jdbc`, no new dependency) with
`record` row mappers. Explicit `UPDATE ... WHERE id = ?` for the refresh path.

### D7 — Seed is a list (ADR-0013)

`POST /clusters` body carries `seedUrls: string[]`. Phase 0 proved advertised
connectors are usually unreachable internal hostnames, so a single seed yields
one manageable node plus a hand-patching queue — not ADR-0004's promised "one
URL". Discovery already keys on `NodeID`, so matching N seeds to N nodes is
near-free. Registration probes each seed, unions the discovered topology, and
attaches each reachable seed URL to the node whose `NodeID` it reports.

### D8 — Corroborated split-brain (ADR-0012)

The refresh loop holds a monotonic `long` cycle counter. Each node row is
stamped with the `observed_cycle` of its last successful read. `HaStateEvaluator`
raises:

- `SUSPECTED` when two rows share `artemis_node_id`, both `active = true`, both
  `observed_cycle == currentCycle`.
- `CRITICAL` when that was also true at `currentCycle - 1` (tracked in memory by
  the evaluator across consecutive runs).

Phase 0 measured failover at ~0.6s; two 5s polls of two nodes are not
simultaneous, so the un-corroborated rule pages on every planned failover.
Worst-case real detection is ~10s — well within human response time.

### D9 — Minimal `@Scheduled` refresh, disposable by design

One `@Scheduled(fixedDelayString = "${artemis-studio.scrape.tier-a-interval}")`
method. Increments the cycle counter, then per cluster per manageable node: one
batched POST (`Active,Started,Backup,ReplicaSync,NodeID,Clustered,Version` +
`listNetworkTopology()`), `UPDATE broker_node` via `JdbcClient`, `last_error` /
`last_seen_at` on failure — never throws. Phase 2 deletes this class whole.
`@ConfigurationProperties` record `ArtemisStudioProperties` binds the existing
`artemis-studio.*` YAML; the dead `jolokia.origin-header` key is removed.

### D10 — Schema changeset 008

`008-node-ha-state.sql`, added to `db.changelog-master.xml`. `ALTER TABLE
broker_node ADD COLUMN observed_cycle BIGINT` (8-byte first), then `active
BOOLEAN`, `replica_sync BOOLEAN` (1-byte last), all nullable, with a
`--rollback` dropping the three. 001–007 are not touched.

### D11 — API shape and errors

Base `/api/v1`. One `@RestControllerAdvice` renders every failure as RFC 9457
`ProblemDetail` with a stable `type` URI per D3 taxonomy so the frontend
switches on `type`, not on message text. Response DTOs are `record`s with no
credential field. `audit_event.username` is the literal `'system'` until Phase 8.

### D12 — Frontend: identity-axis rendering, routerless

Full rationale in the plan's Part 3. Key structural choices: extend `App.tsx`
in place (no router, `vite.config.ts` untouched); a typed `fetch` wrapper +
TanStack Query hooks against the existing `QueryClient`; new semantic tokens in
`theme.css` and **retarget `--as-node-live` / `--as-ok` off `pine-5`** so the
brand green stops doubling as a status light; `PairSpine` renders HA state as
geometry (above/below an identity axis, solid vs dashed border, aligned vs
offset halves) with glyph + word never colour alone; capability ledger uses
`@mantine/code-highlight` (already a dependency) for the `broker.xml`; one
240ms transition when a node crosses the axis, `prefers-reduced-motion` respected.

## Risks / Trade-offs

- **Inferred `MANAGEMENT_WRITE` can be wrong** (a `jolokia-access.xml` whitelists
  reads but not the specific writes Studio will later attempt) → the reason
  string states it is an inference; Phase 3's first real mutation records the
  authoritative outcome.
- **In-memory cross-cycle state in `HaStateEvaluator`** is lost on restart, so a
  split-brain in progress across a restart momentarily drops to `SUSPECTED` →
  acceptable: it re-escalates on the next cycle (~5s), and Phase 2's scheduler
  ownership model will revisit this.
- **`observed_cycle` is a single global counter**, not per-cluster → fine for one
  refresh loop; Phase 2's tiered scheduler replaces the whole mechanism.
- **Removing webflux** could surprise a later phase that assumed reactive SSE →
  ADR-0010 records the decision; re-adding is one dependency line with its own ADR.
- **Seed-list registration** puts more in the request body than ADR-0004's "one
  URL" → ADR-0013 records why; the single-seed case still works unchanged.
- **No auth** on a new mutating API → mitigated only by loopback bind + startup
  WARN; genuinely closed in Phase 8, and called out honestly in the UI.

## Migration Plan

Pre-release, single deployment target (dev compose). Deploy = `just up` picks up
changeset 008 on boot (additive, non-breaking). Rollback = Liquibase
`rollback` of 008 drops three nullable columns; revert the branch. The dev
`ARTEMIS_STUDIO_SECRET_KEY` changes value once — there is no stored ciphertext
in any environment yet, so no re-encryption step exists.
