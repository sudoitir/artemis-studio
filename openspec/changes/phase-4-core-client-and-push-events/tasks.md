# Tasks — Phase 4: Core client, push events, faithful message I/O

Slices are ordered; each ends on `just verify` green and a shippable product.
Do not start a slice until the previous one's gate passes.

## 0. Slice 0 — a real broker in the test suite

- [x] 0.1 Add `src/test/java/io/github/sudoitir/artemisstudio/support/ArtemisIntegrationTest.java` — process-wide singleton `GenericContainer("apache/activemq-artemis:2.44.0")`, dev fixture `broker.xml` mounted, `withReuse(true)`, started in a `static {}` block and never stopped; copy the "never stop it" rationale comment from `PostgresIntegrationTest`. Expose `coreUrl()` / `jolokiaUrl()` helpers.
- [x] 0.2 Rewrite `spike/NotificationSpikeIT` as `broker/core/CoreEventClientIT extends ArtemisIntegrationTest`, un-`@Disabled`; keep its provoke-and-drain structure and both trap comments (poll not `MessageListener`; `useTopologyForLoadBalancing=false` + `reconnectAttempts=0`).
- [x] 0.3 Gate: `./mvnw test -Dtest=CoreEventClientIT` boots the container and prints the expected `_AMQ_NotifType` catalogue. `just verify` green.

## 1. Slice 1 — Core connection, subscriptions, honest capability

- [x] 1.1 `broker/core/CoreUrl.java` — pure `dialable(String)` (bare `host:port` → `tcp://host:port`, passthrough when a scheme is present, null when blank) + unit test.
- [x] 1.2 `broker/core/CoreConnectionSettings.java` record + `BrokerConnections.coreSettingsFor(UUID)` — resolve `kind='CORE'`, fall back to `kind='JOLOKIA_BASIC'`, decrypt via `SecretVault` with the row's own kind as AAD, carry the TLS reference. Unit test the fallback.
- [x] 1.3 `ClusterRequests.RegisterClusterRequest` gains optional `@Valid Credentials coreCredentials`; `RotateCredentialsRequest` gains optional `kind` (default `JOLOKIA_BASIC`); registration persists a `CORE` `broker_credential` row when supplied, sealed separately. Update `ClusterService.register` + its DTO mapping.
- [x] 1.4 `persist/BrokerNodeEntity.applyManualCoreUrl(String)` mirroring `applyManualUrl`; `mergeDiscovered` must respect `manualOverride` for `coreUrl` exactly as for `jolokiaUrl`. `NodeOverrideRequest` becomes `(String jolokiaUrl, String coreUrl)` — both optional, at least one required (bean validation + `ApiExceptionHandler` path). Update `TopologyDiscovery` / node-override controller.
- [x] 1.5 `broker/core/CoreConnectionFactory.java` (`@Component`) — build `ActiveMQConnectionFactory` per the design (flags, timeout, `SslBundles` via the same path `BrokerClientFactory` uses).
- [x] 1.6 `broker/core/BrokerEvent.java` record (cluster, node, type, occurredAt, address, routingName, consumer/session/connection/remoteAddress/username, `Map<String,Object> props`).
- [x] 1.7 `broker/core/NotificationMapper.java` — JMS `Message` → `BrokerEvent`; read only `_AMQ_*` object properties; map `_AMQ_NotifType` onto `CoreNotificationType` with an `UNKNOWN:<raw>` fallback; retain the whole property map. Unit test against a hand-built `Message` per the `_AMQ_NotifType` catalogue.
- [x] 1.8 `broker/core/CoreEventClient.java` (`AutoCloseable`) — `start()` opens conn/session/consumer on `activemq.notifications`; a virtual-thread `drain()` loop with `receive(250)`; `classify(JMSException)` → `Kind`; `State` = `Connected` | `Failed`. Never a `MessageListener`.
- [x] 1.9 `broker/core/CoreSubscriptionManager.java` (`@Component`) — `reconcile(clusterId, endpoints)` (desired = live ∧ dialable core URL; stop removed, start added, `Backoff` gate on retry); `verdictFor(clusterId)`; `forget(clusterId)`; `@PreDestroy` closes all clients with a bounded join. `Backoff` (exp 1s→5m, jitter).
- [x] 1.10 `ScrapeScheduler.tierA` calls `subscriptionManager.reconcile(clusterId, endpoints)` after the persist step, on the virtual-thread pool, outside any transaction. Wire `CoreSubscriptionManager.forget` into `ClusterService.delete` beside the existing `forget()` calls.
- [x] 1.11 `BrokerCapabilities.available(reason, brokerXmlSnippet)` overload; `BrokerXmlSnippets.CORE_ACCEPTOR` snippet.
- [x] 1.12 `CapabilityProbe.assessNotifications` takes the `SubscriptionVerdict` and returns `AVAILABLE` / `UNAVAILABLE(kind-specific snippet)` / `UNKNOWN(not-yet-probed)` per the spec; keep the Jolokia-visible precondition reporting in the reason. Thread the verdict from `ClusterService.capabilities` — no connection opened on that path.
- [x] 1.13 Frontend: delete the `notifications` exclusion and its comment from `capsNeedingSetup` in `web/src/app/ClusterLayout.tsx`. Add the manual Core URL field to the node-detail form; add the optional Core credential fields to the registration form.
- [x] 1.14 Tests: capability truth-table IT against the slice-0 container — full permissions ⇒ `AVAILABLE`; `consume`-only user ⇒ `UNAVAILABLE` + security-setting snippet; no dialable Core URL ⇒ `UNAVAILABLE` + acceptor snippet. Failover IT — stop the primary, assert the subscription moves and `verdictFor` stays `Connected`.
- [x] 1.15 Gate: `just verify` green.

## 2. Slice 2 — broker_event persistence and history API

- [x] 2.1 `src/main/resources/db/changelog/changes/010-broker-events.sql` — `broker_event` table (alignment-ordered columns, `seq BIGINT GENERATED ALWAYS AS IDENTITY` PK, FKs to `cluster` cascade and `broker_node` set-null, three indexes) + `010-broker-event-autovacuum` storage params matching `rr_event`. Add the `<include>` to `db.changelog-master.xml`.
- [x] 2.2 `persist/BrokerEventEntity` (`@Column` mappings; `ddl-auto=validate` must accept it) + `persist/BrokerEventRepository`.
- [x] 2.3 `persist/BrokerEventWriter` (`@Component`) — bounded `ArrayBlockingQueue`, `accept(BrokerEvent)` non-blocking with a per-cluster `dropped` counter, `@Scheduled` `flush()` doing a JDBC batch insert returning generated `seq` (style of `QueueSnapshotUpsert`), `droppedFor(clusterId)`. Wire it as the `sink` for `CoreEventClient`.
- [x] 2.4 `persist/BrokerEventReaper` (`@Component`) — hourly `@Scheduled` `DELETE FROM broker_event WHERE received_at < now() - retention`; copy `MetricSampleReaper` shape.
- [x] 2.5 `config/ArtemisStudioProperties` gains nested `Events(retention=PT72H, bufferSize=10000, flushInterval=PT1S, coalesceWindowMs=1000)`; `application.yml` `artemis-studio.events` block. `SettingsService` gains `EVENTS_RETENTION_HOURS` + `EVENTS_BUFFER_SIZE` keys, validated non-positive-rejected, pushed live in `applyRuntime()`.
- [x] 2.6 `service/BrokerEventService` + `web/EventController` — `GET /api/v1/clusters/{id}/events?type=&nodeId=&address=&from=&to=&page=&size=` → `{ data, count, page, pageSize, dropped, oldestRetained }`. `web/dto/EventViews` + `mapper/EventViewMapper` (MapStruct, `CentralMapperConfig`).
- [x] 2.7 Regenerate `web/openapi.json` via `OpenApiSnapshotTest`; `npm run gen:api` for `web/src/api/schema.d.ts`. Add `useEvents(clusterId, filter)` to `web/src/api/client.ts`.
- [x] 2.8 `web/src/events/EventsView.tsx` (+ `.module.css`) — modelled on `AuditView`: expandable rows with the raw `props` map, URL-as-state via `useSearch`/`useNavigate`, `useDebouncedValue(250)`, status word + colour. When `capabilities.notifications.status !== 'AVAILABLE'`, render the explicit unavailable state (reason + snippet), copying `DlqView`'s stance. Register the route in `router.tsx` (`errorComponent: RouteError`) and add to the `VIEWS` tuple in `ClusterLayout.tsx`.
- [x] 2.9 Tests: writer overflow increments `dropped` instead of blocking; reaper trims at the retention boundary; `EventController` paging/filtering IT; changeset `010` applies **and rolls back** against Testcontainers Postgres. Frontend `EventsView.test.tsx` (MSW history + unavailable-state branch).
- [x] 2.10 Gate: `just verify` green. Feature is complete and usable with no SSE change.

## 3. Slice 3 — the data-bearing SSE events topic

- [x] 3.1 `sse/SseHub` — add `publish(clusterId, topic, Object data, String eventId)` overload; existing `publish(clusterId, topic)` delegates with nulls (signal envelope unchanged). Add `sendTo(subscriber, topic, data, id)` for replay.
- [x] 3.2 `web/StreamController` — `KNOWN_TOPICS` gains `events`, `consumers`, `sessions`, `connections`; accept `Last-Event-ID` header; on subscribe with it, replay `BrokerEventService.since(clusterId, lastId, REPLAY_CAP=500)` before live delivery.
- [x] 3.3 `sse/EventStreamPublisher` — the `BrokerEventWriter` sink: `published(batch, seqs)` fans each event out on the `events` topic with `id = seq`, then `coalescer.touch(clusterId, derivedTopicOf(type))` for the resource-view signal topics.
- [x] 3.4 `sse/TopicCoalescer` — per `(cluster, topic)` trailing-edge emit, at most one per `events.coalesce-window-ms`. Unit test: a 500-event burst yields exactly one `consumers` publish per window.
- [x] 3.5 Frontend `web/src/api/stream.ts` — `Topic` union gains the four names; signal topics keep `invalidateQueries(keys.topic(...))`; `events` appends to a buffer via an `onEvent` callback and tracks `lastSeq` (fed back as `Last-Event-ID` by `EventSource` automatically once `id:` is set). `keys.topic` maps any topic to `['clusters', id, topic]`.
- [x] 3.6 `EventsView` merges the live buffer over the paged history, de-duplicating on `seq`, with pause/resume.
- [x] 3.7 `web/src/test/setup.ts` — minimal `EventSource` stub with a `__emit(name, data)` hook, beside the existing shims.
- [x] 3.8 Tests: backend `Last-Event-ID` replay capped at `REPLAY_CAP`; coalescer burst test. Frontend `EventsView.test.tsx` — a pushed live event through the stub, no duplicate row when the same `seq` arrives twice.
- [x] 3.9 Gate: `just verify` green.

## 4. Slice 4 — faithful message I/O over Core

- [ ] 4.1 Extract `broker/MessageTransport` interface mirroring what `MessageService` calls today. `broker/JolokiaMessageTransport` wraps the existing `MessageBrowser` + `MessageOperations` unchanged. A `Channel` enum (`CORE` | `JOLOKIA`).
- [ ] 4.2 Widen `broker/MessageBrowser.BrowsedMessage.body` from `String` to `byte[]` + `BodyEncoding (TEXT | BYTES)` + `contentType`. `bodyTruncated` / `observedLimitBytes` stay Jolokia-only. Do this as its own commit. Update `MessageService` and `web/dto/MessageViews` (`bodyEncoding: "TEXT" | "BASE64"`, base64 for bytes). Regenerate `web/openapi.json` + `schema.d.ts`.
- [ ] 4.3 `broker/CoreMessageTransport` — `browse` via `QueueBrowser` (local skip/take, real bytes + typed props, no truncation; page past `BROKER_PAGE_CAP` delegates to the Jolokia transport); `detail`; `send` with typed `setXProperty` + `BytesMessage` for `spec.bytes()`. Every by-id / by-filter mutation delegates to the injected Jolokia transport.
- [ ] 4.4 `MessageService.clientFor` → `transportFor(clusterId, resolved)` choosing Core when `subscriptions.verdictFor(clusterId).isConnected()` else Jolokia. Audit / limiter / dry-run / bulk cap / `publishQueuesAfterCommit` untouched. Every message response carries `transport`.
- [ ] 4.5 `web/src/messages/MessageDetailPanel.tsx` — show the serving channel; hex/base64 view when `bodyEncoding === 'BASE64'`; drop the truncation notice when `transport === 'CORE'`.
- [ ] 4.6 Tests: Core-vs-Jolokia browse IT — send a `BytesMessage` with a property longer than the management size limit; Jolokia reports `bodyTruncated: true`, Core returns exact bytes with `transport: "CORE"`. Existing `MessageMutations` / `MessagesView` tests pass untouched. Frontend `MessageDetailPanel.test.tsx` binary path.
- [ ] 4.7 Gate: `just verify` green.

## 5. ADRs and docs

- [ ] 5.1 ADR-0026 — Core client connection model (D1–D4, D13).
- [ ] 5.2 ADR-0027 — data-bearing SSE `events` topic + `Last-Event-ID` replay + coalescing; **extends** ADR-0018 (annotate 0018, do not edit its decision).
- [ ] 5.3 ADR-0028 — `broker_event` persistence, `seq` PK exception, bounded buffer + visible drop counter, reaper (D7, D8).
- [ ] 5.4 ADR-0029 — `MessageTransport`, two implementations, Core for read/write fidelity only (D9, D10). Mark ADR-0021 `superseded` with a link; do not edit its decision text.
- [ ] 5.5 `docs/architecture.md` — make the "Event path (push, Phase 4+)" section real; `docs/broker-management-notes.md` — add a Phase 4 section (what shipped, the failover-follow behaviour, the deep-page fallback). README Phase 4 rows ticked. `openspec/project.md` current-phase line updated.

## 6. Verify end to end

- [ ] 6.1 `just verify` green on a clean tree.
- [ ] 6.2 `just up`, open Events: provoke traffic, watch events land live; `docker stop` the primary and confirm the subscription follows failover and the capability ledger stays `AVAILABLE`.
- [ ] 6.3 Browse a queue holding a binary message on the dev pair: confirm `transport: CORE` and no truncation notice; browse a deep page and confirm the channel falls back to Jolokia in the response.
- [ ] 6.4 Point at a chatty queue: confirm the `dropped` counter increments rather than the buffer growing, and that `consumers` / `sessions` / `connections` invalidations are held to ≤1/s each.
