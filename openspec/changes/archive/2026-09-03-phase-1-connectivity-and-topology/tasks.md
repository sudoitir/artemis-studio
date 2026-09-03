## 1. ADRs and conventions

- [x] 1.1 Write `docs/adr/0009-secret-vaulting-and-broker-tls.md` (AES-GCM at rest, key from env, AAD binding, TLS via SSL bundles)
- [x] 1.2 Write `docs/adr/0010-jolokia-over-restclient.md` (blocking RestClient on virtual threads; `spring-boot-starter-webflux` removed; supersede the WebClient `pom.xml` comment)
- [x] 1.3 Write `docs/adr/0011-jdbcclient-over-spring-data-jdbc.md` (JPA/Hibernate mapped to the Liquibase-owned schema, `ddl-auto=validate`; aggregate save in Data JDBC churns child UUIDs)
- [x] 1.4 Write `docs/adr/0012-corroborated-split-brain.md`; add a "Superseded by ADR-0012" note to the relevant bullet of ADR-0002 with a backlink
- [x] 1.5 Write `docs/adr/0013-seed-is-a-list.md`; add a "Amended by ADR-0013" note to the relevant bullet of ADR-0004 with a backlink
- [x] 1.6 Fix the corrupted `POالسطs` string in ADR-0002 Consequences

## 2. Foundations

- [x] 2.1 `config/ArtemisStudioProperties.java` — `@ConfigurationProperties("artemis-studio")` record binding branding, scrape (tier A/B/C), rate-limit; delete `jolokia.origin-header` from `application.yml`
- [x] 2.2 `security/SecretVault.java` — AES/GCM encrypt(clusterId, kind, plaintext) → (ct, nonce); decrypt reverse; constructor validates `ARTEMIS_STUDIO_SECRET_KEY` decodes to 32 bytes or throws
- [x] 2.3 `SecretVaultTest` — round-trip, wrong-key rejection, tamper (`AEADBadTagException`), AAD binding (cluster A ct fails under cluster B)
- [x] 2.4 `db/changelog/changes/008-node-ha-state.sql` — add `observed_cycle BIGINT`, `active BOOLEAN`, `replica_sync BOOLEAN` to `broker_node` with `--rollback`; include it in `db.changelog-master.xml`
- [x] 2.5 `pom.xml` — remove `spring-boot-starter-webflux` and its `<dependency>` comment
- [x] 2.6 `deploy/compose/compose.dev.yaml` — bind studio to `127.0.0.1:8080`; set `ARTEMIS_STUDIO_SECRET_KEY` to valid base64 of 32 bytes. `deploy/compose/.env.example` — same key note
- [x] 2.7 `config/SecurityConfig.java` — log a startup WARN when the API is unauthenticated and not bound to loopback; fix the stale Flyway Javadoc on `ArtemisStudioApplicationTests`

## 3. Jolokia client

- [x] 3.1 `broker/JolokiaRequest.java` / `JolokiaResponse.java` — records; `JolokiaResponse` exposes per-entry `status`, `value`, `error`, `errorType`
- [x] 3.2 `broker/BrokerMBeans.java` — `search` for `org.apache.activemq.artemis:broker=*`, cache the resolved ObjectName; helpers for queue/acceptor/address MBean names
- [x] 3.3 `broker/BrokerClientFactory.java` — per-cluster `RestClient` (base URL per node, basic auth from `SecretVault`, `SslBundles` when `broker_tls` names one, 3s connect / 10s read)
- [x] 3.4 `broker/JolokiaBrokerClient.java` — `readAttributes`, `exec`, `batch(List<JolokiaRequest>)`; double-parse the JSON-string `value` of `listNetworkTopology()` / `listQueues()`; per-entry status inspection
- [x] 3.5 `broker/BrokerConnectionException.java` + classifier — map failures to `UNREACHABLE` / `UNAUTHORIZED` / `NOT_ARTEMIS` / `WRONG_PATH` / `TLS_FAILED`
- [x] 3.6 `src/test/resources/jolokia/*.json` — verbatim Phase 0 payloads (single attr read, HA multi-read, topology, post-failover topology, mixed-status batch, listQueues)
- [x] 3.7 `JolokiaBrokerClientTest` with `MockRestServiceServer` — double-encoded `value`; batch with one 404 + three 200; post-failover topology missing the `backup` key

## 4. Capability probe

- [x] 4.1 `broker/BrokerCapabilities.java` — record: per-class `status` (`AVAILABLE`/`UNAVAILABLE`/`UNKNOWN`), `reason`, optional `brokerXmlSnippet`
- [x] 4.2 `broker/BrokerXmlSnippets.java` — the `activemq.notifications` `security-setting` (consume + createNonDurableQueue + deleteNonDurableQueue in one block) and the `NotificationActiveMQServerPlugin` with the four `SEND_*` flags, verbatim from the dev fixtures
- [x] 4.3 `broker/CapabilityProbe.java` — `MANAGEMENT_READ` from a read; `MANAGEMENT_WRITE` inferred from `listNetworkTopology()` (reason states the inference and its limit; no broker object ever created); `NOTIFICATIONS` `UNKNOWN` + CORE-acceptor and `activemq.notifications` precondition detection; `MESSAGE_IO` degraded-available when write holds
- [x] 4.4 `CapabilityProbeTest` — all four classes across available / unavailable / unknown; assert no mutating exec is issued

## 5. Persistence

- [x] 5.1 `persist/` `@Entity` classes mapped to `cluster`, `broker_node`, `broker_credential`, `broker_tls`, `audit_event` (columns pinned with `@Column`; `@GeneratedValue` UUID/IDENTITY; `params` as `@JdbcTypeCode(JSON)`)
- [x] 5.2 `ClusterRepository`, `BrokerNodeRepository`, `BrokerCredentialRepository`, `BrokerTlsRepository`, `AuditEventRepository` as Spring Data `JpaRepository` interfaces (derived queries; JPQL `@Query` only where needed); HA-state changes are dirty-checking updates on the managed `BrokerNodeEntity`, never delete+reinsert
- [x] 5.3 `AuditService` — create pending event, update outcome, same transaction as the command; `username` = `'system'`

## 6. Topology and HA

- [x] 6.1 `domain/topology/` records — `LogicalNode`, `NodeEndpoint`, `ClusterTopology`, `ClusterHealth`, `SplitBrainStatus { NONE, SUSPECTED, CRITICAL }`
- [x] 6.2 `domain/topology/TopologyDiscovery.java` — call `listNetworkTopology()`, parse pairs, merge on `(cluster_id, artemis_node_id, ha_role)`; discovered nodes get `core_url` + `discovered=true` + no `jolokia_url`; missing `backup` key is "not announced", not a delete; `manual_override=true` rows never rewritten
- [x] 6.3 seed-to-NodeID matching — attach each reachable seed URL to the node whose `NodeID` it reports
- [x] 6.4 `domain/topology/HaStateEvaluator.java` — `state` from `Started`; `ha_role` from `Backup`/`Clustered`; replication-behind from `Backup && !ReplicaSync`; split-brain per ADR-0012 (same-`NodeID` + both `active` + same cycle → `SUSPECTED`; still true next cycle → `CRITICAL`; cross-cycle readings never escalate)
- [x] 6.5 `HaStateEvaluatorTest` — healthy pair; healthy standby not "down"; replication behind; split-brain first-sighting vs confirmed; the planned-failover cross-cycle case must NOT escalate
- [x] 6.6 `TopologyDiscoveryTest` — pair discovered from one seed; `manual_override` row survives rediscovery unchanged; post-failover single-node topology keeps the backup row

## 7. Scheduler

- [x] 7.1 `scheduler/HaRefreshTask.java` — `@Scheduled(fixedDelayString = tier-a-interval)`; monotonic cycle counter; per manageable node one batched POST; update via `BrokerNodeRepository`; capture `last_error` / `last_seen_at`, never throw
- [x] 7.2 `HaRefreshTaskTest` (or slice) — a failing node records `last_error` and does not abort the other nodes' updates

## 8. API

- [x] 8.1 `web/` request/response records — `RegisterClusterRequest { seedUrls, credentials, tlsBundle? }`, `ClusterSummary`, `ClusterDetail`, `CapabilitiesView`, `TopologyView`, `HealthView`, `NodeOverrideRequest`; no credential field on any response
- [x] 8.2 `web/ClusterController.java` — `POST /api/v1/clusters` (`?dryRun`), `GET /clusters`, `GET /clusters/{id}`, `DELETE /clusters/{id}`, `GET /clusters/{id}/capabilities|topology|health`, `POST /clusters/{id}/rediscover`, `PATCH /clusters/{id}/nodes/{nodeId}`
- [x] 8.3 `web/ApiExceptionHandler.java` — `@RestControllerAdvice` → RFC 9457 `ProblemDetail` with a stable `type` URI per connection-error class and per validation failure
- [x] 8.4 `ClusterControllerTest` (`@SpringBootTest` + Testcontainers PG, `MockRestServiceServer` for Jolokia) — register (persists + audit pending→success), `?dryRun` (no `cluster` row, audit still written), bad seed (classified ProblemDetail, nothing persisted), rediscover keeps an overridden node, `DELETE` removes rows + credentials only

## 9. Frontend

- [x] 9.1 `web/src/theme.css` — add `--as-node-unmanaged`, `--as-axis`, `--as-axis-broken`; retarget `--as-node-live` and `--as-ok` off `pine-5` to a bright neutral
- [x] 9.2 `web/src/api/client.ts` — typed `fetch` wrapper, `ProblemDetail` → typed error, TanStack Query hooks for clusters / capabilities / topology / health
- [x] 9.3 `web/src/clusters/ClusterRail.tsx` — replace the dead nav placeholders; real active state
- [x] 9.4 `web/src/clusters/RegisterCluster.tsx` — inline when empty, modal after; **Check connection** (`?dryRun`) then **Register cluster**; labelled fields, blur validation, error below field, `aria-live` result
- [x] 9.5 `web/src/clusters/PairSpine.tsx` — identity axis with the five states (healthy, replication behind, failed over, split-brain suspected/confirmed, found-not-manageable); geometry + glyph + word, never colour alone; screen-reader summary sentence
- [x] 9.6 `web/src/clusters/CapabilityLedger.tsx` — hanging status column; only not-available rows expand to reason + `broker.xml` via `@mantine/code-highlight` with a copy button
- [x] 9.7 `web/src/clusters/AddManagementUrl.tsx` — supply a management URL for a discovered node → `PATCH`; `RemoveCluster` typed-name confirmation
- [x] 9.8 `web/src/App.tsx` — wire the rail + detail column; drop the `0.1.0 · skeleton` badge; one 240ms cross-axis transition, `prefers-reduced-motion` respected
- [x] 9.9 `npm run build` + `npm run lint` clean

## 10. Docs and close-out

- [x] 10.1 `README.md` — tick the eight Phase 1 checkboxes
- [x] 10.2 `openspec/project.md` — update the "Current phase" line
- [x] 10.3 `just fmt` then `just verify` green (backend + frontend)
- [x] 10.4 Manual acceptance against `just up`: register on `:8161`; backup shows found-not-manageable; add `:8261` override; `rediscover` keeps it; stop primary → failover within ~5s with NO critical split-brain; failback; `?dryRun` writes no cluster row; `audit_event` shows pending→outcome
- [ ] 10.5 `/opsx:archive` the change; commit, push, open PR, merge to `main`
