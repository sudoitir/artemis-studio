## Context

See `proposal.md` — Why. This section covers only the current-state facts that
shape the approach.

- **One transport today.** Everything reaches the broker through
  `broker/JolokiaBrokerClient` (rebuilt per call; the resolved broker MBean name
  is memoised in a `Map` the factory owns). Credentials are per cluster, keyed
  `(cluster_id, kind)`; `SecretVault` seals with AES-GCM and AAD
  `clusterId + "|" + kind`. `broker_credential.kind` already documents
  `JOLOKIA_BASIC | CORE`; the `CORE` row is never written today.
- **`broker_node` already carries `core_url`**, populated by
  `TopologyDiscovery` with the broker-advertised `<connector>` (`host:port`, no
  scheme) — the form that is usually unreachable from where Studio runs.
  `manual_override` protects `jolokia_url` only (`applyManualUrl`).
- **Capabilities are not persisted.** `ClusterService.capabilities(id)` runs a
  live `CapabilityProbe.probe(client)` on every `GET /clusters/{id}`.
  `assessNotifications` is hardcoded `UNKNOWN`.
- **The SSE hub** (`sse/SseHub`, ADR-0018) fans out data-free
  `{topic,clusterId,ts}` signals; `sse/StreamSignals` holds per-cluster
  signatures so an unchanged scrape emits nothing; `web/StreamController` knows
  `KNOWN_TOPICS = {topology, health, queues}` and returns `SseEmitter(0L)`.
  Frontend consumes it in `web/src/api/stream.ts` (`useClusterStream`), which
  **invalidates** the matching TanStack Query key.
- **The scheduler** (`scheduler/ScrapeScheduler`, `implements
  SchedulingConfigurer`) runs tier A/B/C on a virtual-thread `fanOut` /
  `runIsolated` pattern, each node guarded by `scheduler/NodeCallLimiter` (a
  per-node per-second `Semaphore`, topped up by a 1s refill). Network I/O never
  runs inside a transaction; a short `@Transactional` persist step follows.
- **Message ops** (`service/MessageService`) resolve `(node, address,
  routingType)` from the cached `queue_snapshot` row, then: `begin` audit →
  `limiter.acquire` → `MessageBrowser` / `MessageOperations` call →
  `audit.succeed/fail` → `publishQueuesAfterCommit`. `BrowsedMessage.body` is a
  `String`; truncation is detected only by Artemis' literal `", + N more"`
  marker. ADR-0021 fixes this path as Jolokia-only, pending Phase 4.
- **Phase 0 receipts.** `spike/NotificationSpikeIT` (`@Disabled`) is a working
  Core/JMS notification consumer; `docs/broker-management-notes.md` §7–§8 is the
  captured `_AMQ_NotifType` catalogue, per-type headers, both required
  `broker.xml` stanzas (already in `BrokerXmlSnippets` and the dev fixtures),
  and the two Core-client traps. `artemis-jakarta-client:2.56.0` and
  `testcontainers-junit-jupiter` are already on the pom.
- **`007-request-reply.sql`** already defines `rr_expectation` / `rr_flow` /
  `rr_event`, flow-scoped and correlated, for Phase 5 — a different grain from a
  raw notification log.

## Goals / Non-Goals

**Goals:**

- One working product at the end of every slice; slice N+1 never leaves N
  half-built.
- The `MessageTransport` interface is extracted *from two real implementations*,
  not designed ahead of them (ADR-0002).
- Push events reuse the existing SSE hub and the existing resource-view query
  keys; the scrape path is untouched except for the one hook that reconciles
  subscriptions.
- Every honesty non-negotiable (#1 broker-friendly, #2 safe, #3 audited, #4 HA
  from polling, #5 capability-gated) holds by construction, not by a new code
  path that re-implements them.

**Non-Goals:**

- No durable SSE subscriber registry, no multi-instance fan-out (still post-MVP;
  the `broker_event` replay is the reconnection story, not cross-instance).
- No Core implementation of by-id / by-filter mutations (Decision 9).
- No request–reply correlation — that is Phase 5, consuming this phase's event
  stream.
- No new `@ConfigurationProperties` class — the project has exactly one, on
  purpose; new tunables are nested records + `SettingsService` keys.
- No JMX transport (ADR-0002).

## Decisions

### D1 — Poll `consumer.receive(timeout)`, never a `MessageListener`

`NotificationSpikeIT` documents that a JMS `MessageListener` on the notification
consumer deadlocks against `close()` with the pinned 2.56 client / 2.44 broker.
The drain loop is the workaround; the comment must survive refactoring.
*Alternative rejected:* a listener with an executor — same deadlock, it is in
the client's `close()` path, not the callback thread.

### D2 — One subscription per serving node, reconciled off the tier-A tick

Notifications are per-broker. Tier A is already the component that learns who is
live (`Active` polling, ADR-0012). `CoreSubscriptionManager.reconcile(clusterId,
endpoints)` is called at the end of `ScrapeScheduler.tierA`, per cluster, on the
scheduler's virtual-thread pool, never in a transaction — the same discipline as
`runIsolated`. Failover is *followed* (non-negotiable #4): the desired set is
`endpoints.filter(live).filter(hasDialableCoreUrl)`.
*Alternative rejected:* a dedicated Core scheduler — a second thing that has to
learn liveness, duplicating tier A.

### D3 — `useTopologyForLoadBalancing=false`, `reconnectAttempts=0`, Studio drives reconnect

The broker pushes its topology to CORE clients advertising `<connector>` hosts
(`artemis-primary:61616`) that nothing off the broker network resolves →
`AMQ214033` / `AMQ219016`, and a blocking call then wedges. Disabling
topology-driven balancing and the library's own reconnect makes a bad connect
fail fast; `CoreSubscriptionManager` retries with `Backoff` (exp 1s→5m, jitter).
*Alternative rejected:* rely on the client's reconnect — it blocks callers and
retries against the unresolvable advertised host.

### D4 — The subscription sits outside `NodeCallLimiter`

The limiter is a per-second *call* bucket. A long-lived subscription is not a
call; taking a permit for it is meaningless and starves real calls. Connect
*attempts* are bounded by `Backoff` instead.
*Alternative rejected:* a permit per subscription — permanently holds one of ~20
permits per node for nothing.

### D5 — `assessNotifications` reads a cached verdict, never connects

A Core connect on the `GET /clusters/{id}` path would cost a TCP handshake per
page load. `CoreSubscriptionManager.verdictFor(clusterId)` returns the
already-known outcome (`Connected` on ≥1 node / worst `Failed` / `NotAttempted`).
The probe keeps reporting the Jolokia-visible preconditions in its reason text.
*Alternative rejected:* persist a `capability` row — more schema and a staleness
policy for a value the subscription manager already holds in memory.

### D6 — The CORE credential defaults to the Jolokia credential

Registration writes only `JOLOKIA_BASIC` today and most operators use one
account. `BrokerConnections.coreSettingsFor` tries `kind='CORE'`, then falls
back to `kind='JOLOKIA_BASIC'`. A real CORE row, when supplied, is sealed
separately (AAD is `clusterId|CORE`) — never a re-seal of the Jolokia
ciphertext.
*Alternative rejected:* force re-registration for a Core credential — needless
friction for the common single-account case.

### D7 — `broker_event.seq BIGINT GENERATED ALWAYS AS IDENTITY` is the primary key

This table is a log. A monotonic bigint is also the SSE `Last-Event-ID` cursor
for free (replay = `WHERE seq > ?`). The uuid-PK / alignment-ordering
convention is for entities; an append-only stream keyed by insertion order is
the documented kind of exception. Recorded in ADR-0028.
*Alternative rejected:* uuid PK + a separate sequence column — two identifiers
for one ordering.

### D8 — `broker_event` is a new table, not `rr_event`

`rr_event` is flow-scoped (`FK → rr_flow`), correlated, Phase 5. `broker_event`
is cluster-scoped raw notification history with its own retention. Different
grain, lifetime and reader.
*Alternative rejected:* widen `rr_event` to allow a null `flow_id` — couples two
unrelated lifecycles and breaks its `ON DELETE CASCADE`.

### D9 — `CoreMessageTransport` does browse / detail / send; mutations delegate to Jolokia

move / retry / delete / expire / purge are management operations addressed by id
or selector. They carry no payload, so they have no fidelity dimension — a Core
implementation would be a second way to invoke the identical broker operation.
Fidelity is a property of reading and writing a *body*; that is exactly what
gets a Core path. A `QueueBrowser` also has no server-side offset, so a page
past a bounded depth is served over Jolokia, with the response saying so
(non-negotiable #1 — no silent slow path).
*Alternative rejected:* full Core transport — doubles the mutation surface for
no faithfulness gain and a second thing to keep in step.

### D10 — Extract `MessageTransport` now, from the two implementations

ADR-0002: *"the client interface is extracted then, from two real
implementations, not guessed up front."* `JolokiaMessageTransport` wraps the
existing `MessageBrowser` + `MessageOperations` unchanged; `CoreMessageTransport`
is the new one. `MessageService.clientFor` becomes `transportFor`, choosing on
`verdictFor(clusterId).isConnected()`. Everything around the call — audit,
limiter, dry-run, bulk cap, typed confirmation, `publishQueuesAfterCommit` — is
untouched, because the swap is *below* that layer.

### D11 — Notification-derived resource-view invalidation is coalesced to ≤1/s per topic

`consumers` / `sessions` / `connections` / `queues` views are live fan-out reads
(`PagedListService.fanOut`): one Jolokia call per node per invalidation. An
uncoalesced chatty broker turns push into a self-inflicted DoS (non-negotiable
#1). A trailing-edge coalescer per `(cluster, topic)` collapses a burst into one
signal. `StreamSignals`' signature machinery is for poll dedup and is *not*
reused — push events are already edge-triggered.
*Alternative rejected:* emit one signal per notification — a 500-consumer
reconnect storm becomes 500× (nodes) Jolokia calls.

### D12 — No heuristic for "the notification plugin is missing"

An idle broker and a plugin-less broker are indistinguishable by event absence.
When `NOTIFICATIONS` is `AVAILABLE`, ship the `NotificationActiveMQServerPlugin`
snippet anyway and say why in the reason — the same stance `DlqView` takes on
address settings (name the gap, infer nothing).

### D13 — Slice 0 boots a real Artemis in a process-wide singleton container

Nothing in the suite boots a broker today. `support/ArtemisIntegrationTest`
copies the `PostgresIntegrationTest` shape: a `static {}` `GenericContainer`
(`apache/activemq-artemis:2.44.0`, the dev fixture `broker.xml` mounted),
`withReuse(true)`, never stopped (Spring caches contexts across classes; a
per-class `@Container` leaves a cached context pointing at a dead broker). No
new dependency. `NotificationSpikeIT` becomes `broker/core/CoreEventClientIT`,
un-`@Disabled`.

## Pseudocode (the load-bearing pieces)

```java
// broker/core/CoreUrl.java — pure, no I/O
static String dialable(String coreUrl) {           // broker_node.core_url or the manual override
    if (coreUrl == null || coreUrl.isBlank()) return null;
    return coreUrl.contains("://") ? coreUrl : "tcp://" + coreUrl;   // discovery stores bare host:port
}
```

```java
// broker/BrokerConnections.java  (D6)
CoreConnectionSettings coreSettingsFor(UUID clusterId) {
    var cred = credentials.findByClusterIdAndKind(clusterId, "CORE")
        .or(() -> credentials.findByClusterIdAndKind(clusterId, "JOLOKIA_BASIC"))
        .orElse(null);
    if (cred == null) return CoreConnectionSettings.anonymous(clusterId);
    String pw = vault.decrypt(clusterId, cred.getKind(),          // AAD = clusterId|kind
                              cred.getSecretCt(), cred.getSecretNonce());
    var tls = tlsRepo.findByClusterId(clusterId).orElse(null);
    return new CoreConnectionSettings(clusterId, cred.getUsername(), pw,
        tls == null ? null : tls.getTruststoreRef(),
        tls == null || tls.isVerifyHostname());
}
```

```java
// broker/core/CoreConnectionFactory.java  (D3)
ActiveMQConnectionFactory build(CoreConnectionSettings s, String dialableUrl) {
    var f = new ActiveMQConnectionFactory(
        dialableUrl + "?useTopologyForLoadBalancing=false", s.username(), s.password());
    f.setInitialConnectAttempts(1);
    f.setReconnectAttempts(0);                                    // Studio drives reconnect
    f.setCallTimeout(props.broker().readTimeout().toMillis());
    if (s.tlsBundle() != null) applyBundle(f, sslBundles.getBundle(s.tlsBundle()));
    return f;
}
```

```java
// broker/core/CoreEventClient.java  (D1) — one node's subscription, AutoCloseable
enum Kind { NO_CORE_URL, UNREACHABLE, UNAUTHORIZED, PERMISSION_DENIED, TLS_FAILED, UNKNOWN }
sealed interface State { record Connected(Instant since) {} record Failed(Kind k, String why, Instant at) {} }

void start() throws JMSException {
    conn = factory.createConnection();
    conn.start();
    session  = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
    consumer = session.createConsumer(session.createTopic("activemq.notifications"));
    state = new Connected(Instant.now());
    drainThread = Thread.ofVirtual().name("core-notif-" + nodeId).start(this::drain);
}

private void drain() {                                            // D1: poll, never onMessage
    while (running) {
        try {
            Message m = consumer.receive(250);
            if (m != null) sink.accept(mapper.toEvent(clusterId, nodeId, m));
        } catch (JMSException e) {
            if (running) { state = new Failed(classify(e), e.getMessage(), Instant.now()); return; }
        }
    }
}

Kind classify(JMSException e) {                                   // codes from broker notes §7-§8
    String s = String.valueOf(e.getMessage());
    if (s.contains("AMQ229213")) return Kind.PERMISSION_DENIED;   // consume|createNonDurableQueue missing
    if (s.contains("AMQ229031") || s.contains("security")) return Kind.UNAUTHORIZED;
    if (hasCause(e, SSLException.class)) return Kind.TLS_FAILED;
    if (s.contains("AMQ214033") || s.contains("AMQ219016") || hasCause(e, ConnectException.class))
        return Kind.UNREACHABLE;
    return Kind.UNKNOWN;
}
```

```java
// broker/core/NotificationMapper.java
BrokerEvent toEvent(UUID clusterId, UUID nodeId, Message m) throws JMSException {
    var props = new LinkedHashMap<String,Object>();
    for (var e = m.getPropertyNames(); e.hasMoreElements(); ) {
        String n = (String) e.nextElement();
        props.put(n, m.getObjectProperty(n));                     // JMSMessageID is null on notifications
    }
    String raw = str(props.get("_AMQ_NotifType"));
    CoreNotificationType t = parseOrNull(CoreNotificationType.class, raw);   // client enum, not re-declared
    return new BrokerEvent(clusterId, nodeId,
        t == null ? "UNKNOWN:" + raw : t.name(),
        Instant.ofEpochMilli(longOr(props.get("_AMQ_NotifTimestamp"), m.getJMSTimestamp())),
        str(props.get("_AMQ_Address")), str(props.get("_AMQ_RoutingName")),
        str(props.get("_AMQ_ConsumerName")), str(props.get("_AMQ_SessionName")),
        str(props.get("_AMQ_ConnectionName")), str(props.get("_AMQ_RemoteAddress")),
        firstNonNull(props.get("_AMQ_ValidatedUser"), props.get("_AMQ_User")),
        props);                                                   // whole map → jsonb
}
```

```java
// broker/core/CoreSubscriptionManager.java  (D2, D5)
Map<UUID, CoreEventClient> active = new ConcurrentHashMap<>();
Map<UUID, Backoff>         retry  = new ConcurrentHashMap<>();

void reconcile(UUID clusterId, List<NodeEndpoint> endpoints) {   // end of ScrapeScheduler.tierA
    Set<UUID> want = endpoints.stream()
        .filter(NodeEndpoint::live)
        .filter(n -> CoreUrl.dialable(n.coreUrl()) != null)
        .map(NodeEndpoint::id).collect(toSet());
    active.keySet().stream().filter(id -> !want.contains(id)).toList().forEach(this::stop);
    for (UUID id : want) {
        if (active.containsKey(id)) continue;
        if (retry.get(id) != null && retry.get(id).notDueYet()) continue;   // D4
        try { start(clusterId, id); retry.remove(id); }
        catch (Exception e) { retry.computeIfAbsent(id, k -> new Backoff(ofSeconds(1), ofMinutes(5)))
                                   .recordFailure(); }
    }
}
SubscriptionVerdict verdictFor(UUID clusterId) { /* Connected(n, since) | Failed(kind, why) | NotAttempted */ }
void forget(UUID clusterId) { /* stop all; wire into ClusterService.delete beside the other forget()s */ }
```

```java
// persist/BrokerEventWriter.java  — the sink handed to CoreEventClient
BlockingQueue<BrokerEvent> buf = new ArrayBlockingQueue<>(props.events().bufferSize());   // 10_000
Map<UUID, AtomicLong> dropped = new ConcurrentHashMap<>();

void accept(BrokerEvent e) {                                     // never blocks the drain thread
    if (!buf.offer(e)) dropped.computeIfAbsent(e.clusterId(), k -> new AtomicLong()).incrementAndGet();
}
@Scheduled(fixedDelayString = "${artemis-studio.events.flush-interval:PT1S}")
void flush() {
    var batch = new ArrayList<BrokerEvent>(500);
    buf.drainTo(batch, 500);
    if (batch.isEmpty()) return;
    List<Long> seqs = jdbc.batchInsertReturningSeq(batch);       // style of QueueSnapshotUpsert (ADR-0016)
    publisher.published(batch, seqs);                            // no-op until slice 3
}
```

```java
// sse/EventStreamPublisher.java  (D11)
void published(List<BrokerEvent> batch, List<Long> seqs) {
    for (int i = 0; i < batch.size(); i++)
        hub.publish(batch.get(i).clusterId(), "events", view(batch.get(i), seqs.get(i)),
                    String.valueOf(seqs.get(i)));                 // data + event id
    for (var e : batch) {
        String derived = switch (e.type()) {
            case "CONSUMER_CREATED","CONSUMER_CLOSED"        -> "consumers";
            case "SESSION_CREATED","SESSION_CLOSED"          -> "sessions";
            case "CONNECTION_CREATED","CONNECTION_DESTROYED" -> "connections";
            case "BINDING_ADDED","BINDING_REMOVED",
                 "ADDRESS_ADDED","ADDRESS_REMOVED"           -> "queues";
            default -> null;
        };
        if (derived != null) coalescer.touch(e.clusterId(), derived);   // trailing edge, ≤1 / window
    }
}
```

```java
// sse/SseHub.java — signal topics unchanged; one overload added
void publish(UUID c, String topic) { publish(c, topic, null, null); }
void publish(UUID c, String topic, Object data, String id) {
    Object payload = data != null ? data
        : Map.of("topic", topic, "clusterId", c.toString(), "ts", Instant.now().toEpochMilli());
    for (Subscriber s : subscribersOf(c)) if (s.wants(topic)) {
        var ev = SseEmitter.event().name(topic).data(payload);
        if (id != null) ev.id(id);
        trySend(c, s, ev);
    }
}
```

```java
// web/StreamController.java
static final Set<String> KNOWN_TOPICS =
    Set.of("topology","health","queues","events","consumers","sessions","connections");

SseEmitter stream(UUID clusterId, String topics, @RequestHeader(name="Last-Event-ID", required=false) Long last, …) {
    var sub = new Subscriber(new SseEmitter(0L), parseTopics(topics));   // still filters unknowns, falls back to all
    hub.register(clusterId, sub);
    if (last != null && sub.wants("events"))
        eventService.since(clusterId, last, 500)                 // bounded replay (D7)
            .forEach(e -> hub.sendTo(sub, "events", view(e), String.valueOf(e.seq())));
    …
}
```

```java
// broker/MessageTransport.java  (D10) — mirrors what MessageService already calls
interface MessageTransport {
    Channel channel();                                           // CORE | JOLOKIA — surfaced to the UI
    BrowsePage browse(ResolvedQueue q, int page, int size, String filter);
    Optional<BrowsedMessage> detail(ResolvedQueue q, long id, String filter);
    String send(ResolvedQueue q, SendSpec spec);
    long countMessages(ResolvedQueue q, String filter);   long messageCount(ResolvedQueue q);
    long moveByIds(ResolvedQueue q, List<Long> ids, String target);
    long retryByIds(ResolvedQueue q, List<Long> ids);     long deleteByIds(ResolvedQueue q, List<Long> ids);
    long expireByIds(ResolvedQueue q, List<Long> ids);
    long moveByFilter(ResolvedQueue q, String f, String target);
    long deleteByFilter(ResolvedQueue q, String f);       long expireByFilter(ResolvedQueue q, String f);
    long retryAll(ResolvedQueue q);   long purge(ResolvedQueue q);
}

// CoreMessageTransport  (D9)
final class CoreMessageTransport implements MessageTransport {
    private final MessageTransport jolokiaFallback;
    public Channel channel() { return Channel.CORE; }
    public BrowsePage browse(ResolvedQueue q, int page, int size, String filter) {
        if ((long) page * size > BROKER_PAGE_CAP) return jolokiaFallback.browse(q, page, size, filter);  // no offset
        try (var s = session()) {
            var b = (filter == null || filter.isBlank())
                ? s.createBrowser(s.createQueue(q.queueName()))
                : s.createBrowser(s.createQueue(q.queueName()), filter);
            // walk, skip (page-1)*size locally, take size; real byte[] body, real typed props, no truncation
        }
    }
    public String send(ResolvedQueue q, SendSpec spec) { /* typed setXProperty + BytesMessage when spec.bytes() */ }
    public long moveByIds(ResolvedQueue q, List<Long> ids, String t) { return jolokiaFallback.moveByIds(q, ids, t); }
    // …every other mutation delegates identically (D9)
}
```

```java
// service/MessageService.java
private MessageTransport transportFor(UUID clusterId, ResolvedQueue r) {
    return subscriptions.verdictFor(clusterId).isConnected()
        ? coreTransports.forNode(clusterId, r.nodeId())
        : jolokiaTransports.forNode(clusterId, r.nodeId());
}
// begin audit → limiter.acquire (Jolokia calls only) → transport call → audit.succeed/fail
// → publishQueuesAfterCommit  — all unchanged
```

## Risks / Trade-offs

- **Advertised connector unresolvable off the broker network** (`AMQ214033` /
  `AMQ219016`) → D3's factory flags fail the connect fast; the manual Core URL
  override (per node, survives rediscovery) is the fix; `manual_override`
  already exists for the Jolokia side.
- **`MessageListener` deadlock on the pinned client/broker pair** → D1 poll
  loop; the constraint is commented at the loop and called out in ADR-0026 so a
  later refactor does not "modernise" it into a listener.
- **`broker_event` write amplification from a chatty broker** → bounded buffer +
  visible `dropped` counter on every events API response + hourly reaper; the
  first load test (§tasks) sets whether 72h default retention is right.
- **`QueueBrowser` deep-page cost is O(page × size)** → capped at
  `BROKER_PAGE_CAP`; past it the call is served over Jolokia and the response's
  `transport` field says the channel changed — no silent slow path.
- **`BrowsedMessage.body` widening `String` → `byte[]`** touches the DTO, the
  OpenAPI snapshot, the generated TS types and two frontend tests → done as its
  own commit inside slice 4 so a regression bisects cleanly.
- **Cached capability verdict can lag a just-fixed broker.xml by up to one
  tier-A interval** → acceptable; the reason text says "as of the last probe",
  matching how HA state is already surfaced.
- **Scope** — largest change since Phase 2 (four ADRs, a changeset, a new
  transport, a new screen). Each slice ships; stopping after slice 3 still
  leaves a coherent release and slice 4 becomes its own change.

## Migration Plan

- **Schema:** one forward changeset, `010-broker-events.sql`, appended to
  `db.changelog-master.xml`. Rollback is `DROP TABLE broker_event`. Boot runs it;
  `just db-*` for humans. No data backfill — the table starts empty.
- **Config:** `application.yml` gains an `artemis-studio.events` block; absent
  keys fall back to the nested-record defaults, so an un-updated config still
  boots. `events.retention-hours` / `events.buffer-size` become
  `studio_setting`-overridable.
- **Deploy order:** backend and frontend ship together (the `events` SSE topic
  name must exist on both sides or `parseTopics` silently drops it; the
  `transport` / `bodyEncoding` response fields are additive and the old UI
  ignores them).
- **Rollback:** revert the app image and run the `010` rollback. Nothing in
  001–009 changes, so a downgrade is clean. The Core client is inert if
  `core_url` is unset and no CORE credential is stored — a partial rollout that
  ships the backend without configuring any Core URL simply keeps Phase 3
  behaviour with `NOTIFICATIONS` reported `UNAVAILABLE (no Core URL)`.
- **ADRs:** 0026–0029 added; 0021 marked `superseded` with a link (its decision
  text untouched); 0018 gets an annotation pointing at 0027.
