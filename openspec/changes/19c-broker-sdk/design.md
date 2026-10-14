# Design: 19c-broker-sdk

## Context

Rahdar is built for one broker. 18 files import `org.apache.activemq`, 13 modules call
`JolokiaBrokerClient` with raw MBean operation strings, and the data model uses Artemis nouns (address,
routing type, divert). Rahdar is to manage Kafka, RabbitMQ and ActiveMQ Classic too, in one estate
view: clusters of any kind side by side, shared screens across all of them, and system-only screens
contributed per system. The abstraction is also a product of its own: a framework-free **broker SDK**
that other projects, with other brokers, can adopt. This change builds that SDK, moves Artemis behind
it as its first driver, and makes Rahdar's domain and data model broker-neutral, **with no behaviour
change for Artemis**.

The SDK is called **OpenBroker** (artifacts `openbroker-*`, packages `io.github.sudoitir.openbroker…`). The
name is deliberately not the product's brand, so tools for other brokers can depend on it, as R2DBC,
OpenFeature and OpenTelemetry show.

## Goals / Non-goals

Goals:
- A broker-neutral SDK (`openbroker-api`, `-support`, `-tck`, `-bom`) with a driver SPI, good enough for
  outside adoption.
- The Artemis driver (`openbroker-artemis`), holding every line of Artemis-specific transport code.
- Every Rahdar feature on the SDK's facets, with the same behaviour, wire traffic and load on Artemis.
- A broker-neutral data model.

Non-goals:
- The public REST, MCP and UI vocabulary and the plugin contract's message types. 19d makes those
  neutral; this change keeps them, mapping at the view layer.
- Other drivers (19e Kafka, 19f RabbitMQ, 37 ActiveMQ Classic).
- The product rename (19b).

## Decisions

Prior-art reviews shaped every name below. The ADRs written during apply cite them.

## Design principles

- **Ports and adapters.** The SDK is the port, a driver is an adapter, and Rahdar is the core.
- **Bridge, as in JDBC.** A stable API plus drivers found through `ServiceLoader`, metadata for
  capabilities, and `unwrap()` for vendor extensions.
- **Type Object**. Each resource has an open, driver-defined **kind**
  (`artemis.queue`, `kafka.group-topic`) that maps to a closed, neutral **role**. Shared code reasons
  about roles; kinds carry native names, attributes and schemas.
- **Standards for the vocabulary.** The OpenTelemetry messaging conventions (destination, subscription and
  consumer group as separate concepts), UCUM units on metrics, and the other prior art listed in section 2.
- **GRASP and SOLID.**
  - Information Expert: a resource carries its own state, and metrics are numeric attributes over time.
  - Polymorphism and Protected Variations: drivers vary behind facets.
  - Interface Segregation: one facet per concern; optional facets are `Optional`.
  - Pure Fabrication: `Access`, `Partial`, `Position`, `Scope`.
  - Indirection: the Rahdar gateway.
- **One extension mechanism.** Native features go through `unwrap`, never as a `Native` member of the
  operation hierarchy. Native *event types* are allowed: events are data, not behaviour.
- **A sealed Command hierarchy** for operations, with exhaustive `switch`.

## 1. Artifacts and layout

The Rahdar repo becomes a Maven reactor with a `pom`-packaged root:

| Module | Contents | Depends on |
|---|---|---|
| `openbroker-api` | Consumers and drivers share these types, as in JDBC, R2DBC, SLF4J and OpenFeature. Two packages: `io.github.sudoitir.openbroker` (the consumer API) and `.spi` (`BrokerDriver`, `DriverContext`, `CallInterceptor`). Java 25. `module-info.java` with `uses`/`provides`. No dependencies except JSpecify. | – |
| `openbroker-tck` | The driver conformance kit: abstract JUnit 5 contract tests. The driver supplies the broker. | api, JUnit |
| `openbroker-artemis` (driver, R2DBC-style name) | All Jolokia, Core, MBean, notification, relay and broker.xml-snippet code, moved out of Rahdar. Its tests move with it, without Spring, and the TCK runs in its integration tests. | api, artemis-jakarta-client, Jackson |
| `openbroker-support` | Shared implementation code for drivers and consumers, so `-api` stays interface-only (the OTel api/sdk reason): the portable `openbroker.match` filter evaluator, the polling watch helper (Kubernetes informer: diff successive `read`s into events, with `Gap` on a missed cycle), and rate and counter-reset logic moved out of Rahdar. | api |
| `openbroker-bom` | Version alignment for adopters. | – |
| `rahdar/` (with `web/`) | The app, artifactId unchanged. | api, support, `openbroker-artemis` |

- The SDK artifacts are published to Central when they change, with their own SemVer (`0.x` until stable).
- These follow the move:
  - the Dockerfile (`COPY` paths, the exec jar path) and the justfile;
  - `ci.yml`, `frontend-maven-plugin` paths, osv-scanner and Dependabot;
  - Sonar (`sonar.sources` and tests per module) and JaCoCo `report-aggregate`.
- japicmp:
  - On `openbroker-api`, all public API (the first release tolerates having no previous version).
  - On the app, the `@PluginApi` include.
  - `BrokerCommands` and `BrokerClientFactory` lose `@PluginApi`, because they expose Jolokia. That is a
    contract break, so `Contract.VERSION` is bumped in 19c.
- ArchUnit:
  - No `org.apache.activemq..` or `org.jolokia..` outside the driver.
  - Rahdar never imports `io.github.sudoitir.openbroker.artemis..`, and only the gateway imports
    `io.github.sudoitir.openbroker.spi`. Packages: `io.github.sudoitir.openbroker` (API), `.spi`, `.support`, `.tck`,
    `.artemis`.
  - `unwrap(...)` is used only inside `system/artemis`.
  - `PluginValidator` builds its deny-list from driver-declared native packages.

## 2. The SDK (`openbroker-api`)

Prior art behind each choice:
- JDBC and R2DBC: the driver bridge, `unwrap`, and connection options with a `sensitive` flag.
- Kubernetes: kinds and verbs, conditions, preconditions, list pagination, relist after a watch gap.
- OpenTelemetry messaging semconv: destination, subscription and consumer group as separate concepts;
  partition; settle; metric instruments and units.
- Terraform: plan before apply, and attributes that need a replace.
- Dapr: component metadata as the connection schema.
- CloudEvents: the event envelope.
- gRPC: canonical status codes.
- EIP and JMS: the delivery semantics names.

```
spi   BrokerDriver { BrokerSystem system(); ConnectionSchema schema();
                     ConnectionReport verify(ClusterSpec, CredentialSource, DriverContext);
                     ClusterConnection connect(ClusterSpec, CredentialSource, DriverContext); }
      BrokerSystem(id, name, supportedVersions, schemaVersion)
      ConnectionSchema: List<OptionSpec(key, type, required, sensitive, default, allowedValues, description, Plane)>
      DriverContext(CallInterceptor calls, TlsProvider tls, Clock clock)
      CallInterceptor { Permit admit(CallInfo(node, plane, operation)); }      // operation names are low-cardinality
      Permit extends AutoCloseable { void complete(Outcome, Optional<Instant> serverTime); }
      Plane { MANAGEMENT, DATA }    CredentialSource.forPlane(Plane)          // rotation: asked per (re)connect
      CallScope(deadline, principal, traceContext, approvalId)   static final ScopedValue<CallScope> CALL   // bound by the caller, read by drivers

ClusterConnection extends AutoCloseable (thread-safe):
      nodes() resources() operations() messages() access()   // close() ends every Receiver, Sender and watch
      Optional<Transport> transport()   Optional<Events> events()   <T> Optional<T> unwrap(Class<T>)
```

**Identity and the model (Type Object)**
- `ResourceId(KindId, ResourcePath)`: logical and cluster-wide. `ResourcePath` is a `List<String>` of
  segments.
  - Canonical text form in Google AIP-122 style (`artemis.queue/orders/eu`, percent-encoded), with
    `parse`/`format` and a TCK round-trip test. It is the one form used by REST, MCP, audit, URLs and the
    database.
- `Resource(ResourceId, Attributes, List<Condition>, List<Placement>, Instant observedAt)`.
  - `observedAt` is the broker's sample time.
  - `Condition(type, TRUE|FALSE|UNKNOWN, reason, message, lastTransition)`: the Kubernetes convention, for
    health comparable across systems (paging or address full, under-replicated, unsynchronised quorum,
    paused).
  - `Placement(NodeId, Optional<PartitionId>, INSTANCE|LEADER|FOLLOWER, Attributes)`.
- `Kind(id, Role, Set<Verb> verbs, Optional<KindId> parent, AttributeSchema)`. These are static
  capabilities, like Kubernetes `APIResource.verbs`. UI labels live outside the SDK.
- `AttributeSchema` entries are `(key, category CONFIG|STATUS|METRIC, writable, required, immutable,
  requiresReplace)`: the Kubernetes and Crossplane spec/status split.
  - `METRIC` entries carry `Measure(instrument, unit)`: an OTel instrument (`Counter`, `UpDownCounter`,
    `Gauge`) and a UCUM unit. Values carry `Precision { EXACT, ESTIMATE }` and a per-`Placement` breakdown
    (per partition for Kafka lag, the way KEDA sums it).
  - Typed relations are `AttributeKey<ResourceId>` (for example a subscription's `destination`), like
    Kubernetes `ownerReferences`.
- `Role { NAMESPACE, DESTINATION, PARTITION, SUBSCRIPTION, CONSUMER_GROUP, ROUTE, POLICY,
  ACCESS_RULE, PRINCIPAL, CONNECTION, SESSION, PRODUCER, CONSUMER }`. Every member a known system needs is
  in before `1.0`, because adding one later breaks exhaustive `switch`es.
  - Nodes are not resources: `Nodes.describeCluster()` returns `NodeDescription`s (one model, no duplicate
    `NODE` role). Placements and `Shard`s reference them by `NodeId`.
  - `SUBSCRIPTION` = a backlog positioned on exactly one destination (typed `destination` relation): what
    you browse, measure, purge and seek. Kafka's is `kafka.group-topic`, child of `CONSUMER_GROUP`.
    This follows OTel's `destination.subscription.name` and `consumer.group.name`, and Spring Cloud
    Stream's destination + group.
  - `DESTINATION` carries `Set<Delivery>` with `Delivery { POINT_TO_POINT, PUBLISH_SUBSCRIBE }` (the EIP
    and JMS names). It is a set because an Artemis address can be both anycast and multicast.
  - `PARTITION` covers Kafka partitions, RabbitMQ super-stream partitions and Pulsar partitions.
  - `ROUTE` is an edge: `source`, `target`, `filter`, `scope LOCAL|REMOTE`,
    `routeClass BINDING|FORWARD|LINK`, and `derived` with a reason. Dead-letter and expiry edges are
    derived from policy.
  - `POLICY` paths are match patterns in the system's own grammar. `ACCESS_RULE` is separate, because
    it references principals and carries a different approval risk.
  - Kafka topic configuration is the topic's own `CONFIG` attributes, not a policy.
- Standard metric keys use OTel style under an SDK-owned prefix (OTel defines no backlog metric):
  - `<p>.backlog {message}`, `<p>.backlog.size By`, `<p>.backlog.age s`;
  - `<p>.in_flight`, `<p>.scheduled`;
  - `<p>.enqueued`, `<p>.acknowledged` and `<p>.expired` (Counters);
  - `<p>.consumer.count`.
  - Native keys are namespaced (`artemis.routing_type`).
  - The ADR maps each key to `artemis_message_count`, `rabbitmq_queue_messages_ready` and
    `kafka_consumergroup_lag`, and says the backlog metrics are a candidate to propose upstream to OTel.

**Facets**
- `Nodes.describeCluster()` returns `ClusterDescription`: `NodeDescription`s (native id, HA membership,
  advertised endpoints per `Plane`, version, conditions), as in Kafka `Admin.describeCluster()`.
- `Resources`:
  - `read(List<ResourceQuery>)` returns `Partial<List<Resource>>`. It makes one round trip per node, with
    projections, and records per-node failures.
  - `ResourceQuery(role|kind, namePattern, fieldSelector over attributes, projection, Scope, limit,
    continueToken)`. Pagination is Kubernetes list chunking.
  - `kinds()` returns this cluster's kinds.
- `Operations`:
  - `preview(id, Operation)` returns `Preview(diff, requiresReplace, estimatedCount, stateKey)`.
  - `execute(id, Operation<R>, Scope)` returns `Partial<R>`.
  - Each operation can carry `expect(stateKey)`, a precondition the driver checks. The approval gate's
    `Effect(count, stateKey)` comes from here: Terraform's plan and Kubernetes' preconditions.
  - The sealed `Operation` permits:
    - `Create` (idempotent: `ALREADY_EXISTS` when it differs);
    - `Update` (a merge patch of writable `CONFIG`);
    - `Delete` (with `ifEmpty`);
    - `Pause`, `Resume`;
    - `Purge` on a `SUBSCRIPTION`;
    - `Seek(Position)` on a `SUBSCRIPTION`;
    - `Truncate(Position)` on a `DESTINATION` or `PARTITION`. It removes data for every subscription
      (Kafka `deleteRecords`), so its effect differs from `Purge`.
    - `ResetCounters`, `Close`.
  - Rahdar's staging and tap queues are a `Create` of a `SUBSCRIPTION` with standard attributes
    (`dead_letter: none`, node-local placement, `managed_by`), removed with `Delete(ifEmpty)`. There is
    no separate holding facet.
- `Scope = Whole | Shards(Set<Shard>)`, where `Shard = NodeInstance(NodeId) | Partition(PartitionId)`.
  It replaces per-node arguments everywhere.
- `Messages`:
  - `browse(id, Scope, BrowseRequest(Position, limit, MessageFilter))` returns
    `BrowsePage(messages, next: Cursor, Total)`.
    - `Position = Earliest | Latest | AtOffsets(Map<Shard, Long>) | AtTime(Instant) | After(Cursor)`.
    - `Total = Exact(n) | Estimate(n) | Unavailable(reason)`. Kafka lag is an estimate.
  - `count(id, Scope, filter)` and `countBy(id, MessageField)` (DLQ triage).
  - `send(SendTarget, OutboundMessage)` returns `SendReceipt`.
    - `SendTarget = ToDestination(id, Optional<routingKey>, Optional<PartitionId>) | ToSubscription(id)`.
    - `OutboundMessage.partitionKey` is separate from `routingKey`.
  - `bulk(id, Scope, Selection, MessageAction)` returns `BulkOutcome` with the `notDone` refs.
    - `Selection = ById | ByFilter(filter, Optional<Instant> producedAtOrBefore) | All`.
    - `MessageAction = Move | Copy | Delete | Retry | Expire`.
- `Message`:
  - `MessageRef(source, Shard, opaque position)`.
  - `MessageBody(Optional<byte[]>, size, truncated)`; a Kafka tombstone is not an empty body.
  - `MessageHeaders` (id, correlation id, reply-to, content type, type, partition key, repeatable byte
    headers) and user properties.
  - `extensions`, keyed by namespace in the CloudEvents style: `jms` (body type, for verbatim relay),
    `artemis`, later `kafka` and `amqp`.
- `MessageFilter(dialect, expression)`. The dialect is driver-declared (`artemis.core-filter`). The SDK also
  defines one portable dialect, `openbroker.match` (exact and prefix matching over headers and properties, as in
  the CloudEvents Subscriptions filter dialects). `-support` evaluates it client side when a driver has no
  server-side support, so DLQ triage works on every system.
- `Transport` (the NServiceBus and MassTransit term for this layer):
  - `receiver(id, Scope, ReceiveOptions)` returns a `Receiver` with `receive(max, timeout)` and
    `settle(ReceivedMessage, Disposition{ACCEPT, RELEASE, REJECT, MODIFY})`, the AMQP 1.0 outcomes and OTel `settle`.
  - `sender(SendTarget, SenderOptions)` returns a `Sender` with `send(msg, Optional<idempotencyKey>)`.
  - A transacted batch (`commit()`/`rollback()` on both) is the capability `transport.transacted`.
    Artemis has it, which keeps today's relay protocol (transacted batch, duplicate refusal, two commits).
    A driver without it declares it unavailable, and Rahdar's relay picks a settle-based strategy.
- `Events.watch(Scope, EventListener)` returns an `AutoCloseable`; `state()` returns per-node stream
  state.
  - `BrokerEvent` is a CloudEvents-shaped envelope (`id`, `source` = node, `type`, `subject` = resource,
    `time`, `data`).
  - The standard types are resource created and deleted, client connected and disconnected, consumer
    attached and detached, plus native types.
  - `Gap(node, since)` tells the consumer to relist: Kubernetes watch semantics. Artemis notifications
    are lossy on reconnect.
  - Events are data, so native types are a namespaced `type` string. This is not a second extension
    mechanism for behaviour.
  - A system with no push events builds `events()` from the `-support` polling watch helper, so adopters
    get events without Rahdar.
- `Access` (dynamic capability, like Kubernetes `SelfSubjectAccessReview`):
  - `check(Capability, Optional<ResourceId>)` returns `Assessment(status AVAILABLE|UNAVAILABLE|UNKNOWN,
    reason, Optional<Remedy(text, snippet, mediaType)>)`.
  - `probe()` returns a `Snapshot`.
  - `Capability` is open: standard constants plus namespaced driver ids. The static half is `Kind.verbs`.
  - Evidence learned from failed calls stays in Rahdar's ledger.
- Errors: one `BrokerException(Status, retryable, Optional<NodeId>, nativeCode)`.
  - `Status` uses gRPC's canonical codes: `CANCELLED`, `UNKNOWN`, `INVALID_ARGUMENT`,
    `DEADLINE_EXCEEDED`, `NOT_FOUND`, `ALREADY_EXISTS`, `PERMISSION_DENIED`, `RESOURCE_EXHAUSTED`,
    `FAILED_PRECONDITION`, `ABORTED`, `UNIMPLEMENTED`, `INTERNAL`, `UNAVAILABLE`, `UNAUTHENTICATED`.
  - `PERMISSION_DENIED` versus `FAILED_PRECONDITION` reproduces `ManagementRefusal.Kind.AUTHORIZATION`
    exactly.
- `Partial<T>(value, Map<NodeId, BrokerException> failures)`. For writes it states which nodes applied,
  that no rollback is attempted, and whether a retry is safe.
- Runtime:
  - Blocking calls, designed for virtual threads (Java 25, no pinning in `synchronized`).
  - Interruption is cancellation (`CANCELLED`). Deadlines come from `CallScope` (default: the driver options), and running out raises `DEADLINE_EXCEEDED`.
  - A `ClusterConnection` is thread-safe. `Receiver`, `Sender` and watches are `AutoCloseable` and single-owner.
  - A `Cursor` is valid across reconnects of the same connection, and invalid after the subscription is
    recreated (`FAILED_PRECONDITION`).
  - `CredentialSource` is asked on every (re)connect, so a rotated secret takes effect on the next connect.
  - `Permit.complete(..., serverTime)` stays: only the driver sees the broker's response time, and Rahdar
    derives the clock offset from it.
  - Logging goes through `System.Logger`.

**How the roles map onto each system**

| | Artemis | Classic | RabbitMQ | Kafka |
|---|---|---|---|---|
| DESTINATION | address | queue / topic | exchange | topic |
| PARTITION | – | – | super-stream partition | partition |
| SUBSCRIPTION | queue | queue, durable subscriber | queue (classic, quorum, stream) | group-topic |
| CONSUMER_GROUP | – | – | – | consumer group |
| ROUTE | binding, divert, bridge | virtual topic, composite, network connector | binding, e2e binding, shovel, federation | – |
| POLICY / ACCESS_RULE | address-settings / security-settings | destination policy / authorization entries | policy / permissions | – / ACLs |
| browse | yes | yes (depth-capped) | streams only | yes, by offset or time |
| move/delete by id | yes | yes | unimplemented | unimplemented |
| purge / seek / truncate | purge | purge | purge | seek; truncate = deleteRecords |
| transacted transport | yes | yes | no (settle) | yes (transactions) |
| events | Core notifications | advisories | event exchange | none |

## 3. Rahdar on the SDK

- `platform/broker` becomes the **gateway**.
  - One `ClusterConnection` per cluster from the driver registry.
  - Rahdar's `CallInterceptor` implementation covers the per-node limiter, `BrokerCallGuard`,
    `NodeCallHealth`, Micrometer observations and the clock offset.
  - Credentials come from Rahdar secrets.
  - It reconnects on a topology or TLS change.
  - Rahdar keeps the URL-pattern node discovery, gzip body decoding and `SendNames` checks.
- Generic features move onto facets: resources, scrape, metrics, queues, messages, bulk, triage, events,
  rr, flow, sql capture, plugin messaging, transfer, alerting.
- Artemis-only product features move to the Rahdar module `system/artemis`, through
  `unwrap(ArtemisManagement.class)`: brokerconfig (broker.xml), the Artemis setup rule packs, and remedy
  snippets.
- Neutral data model (baselines edited in place):
  - `cluster.system`.
  - `broker_node.native_node_id`, plus a `broker_node_endpoint(node, plane, url)` table instead of
    `jolokia_url`/`core_url`.
  - `queue_snapshot` becomes `subscription_snapshot`; the routing type moves to native attributes.
  - The `metric_sample` metric names become the standard keys.
  - `message_index`, `broker_event`, `flow_*`, `rr_*`, `api_token_grant`, `plugin_message_registration`,
    `bulk_run_item` and `classification_finding` move to `destination`/`subscription`/`kind` naming.
  - `ResourceKind{QUEUE,ADDRESS}` becomes the roles `SUBSCRIPTION`/`DESTINATION`.
- The approval gate takes an operation's `Effect(count, stateKey)` from `Operations.preview`, and passes the
  `stateKey` back as `expect(...)` when the held action runs.
- The public edge (REST, MCP, UI wording and the plugin message types) keeps its current names in this
  change. View mappers translate them, and 19d renames them.
- Metric history: the stored metric names change to the standard keys (a breaking change in this
  change), and alert rules and Grafana dashboards are updated with them.

## 4. Order (each group leaves a working product)

1. **Reactor move.** `git mv` the app into `rahdar/`, add the parent pom, and fix the Dockerfile, CI,
   Sonar and JaCoCo. Proof: green CI, `docker build`, `just demo`.
2. **Characterization harness, before any code moves.** A recording HTTP proxy in front of a
   Testcontainers broker captures golden files of the Jolokia request bodies, headers and per-node batch
   counts for scrape tier A, browse, purge, bulk move, the capability probe and staging. These files are
   the "no behaviour change" proof for load and wire format.
3. **`broker-api` plus the TCK skeleton**, with unit tests.
4. **Driver extraction, unchanged internally.** Cross-cutting concerns go through `CallInterceptor`.
   Spring `RestClient` becomes JDK `HttpClient`, and preemptive Basic auth, error mapping, observation
   names and tags, SSL eviction, HTTP/1.1 and timeouts are preserved. Proof: the golden files plus a
   meter-name test.
5. **Gateway.**
6. **Features onto facets, in rising order of risk.** Each ends with that feature's tests and the TCK
   cases for its capability:
   - resources and scrape, then metrics;
   - queue lifecycle;
   - browse, send and mutate, then bulk and triage;
   - notifications onto `Events` (per-node reconciliation, the verdict cache, close under load);
   - rr and flow;
   - capture, SQL and plugin messaging onto `Create`d subscriptions + `Transport`;
   - transfer and relay last. The relay ITs (duplicate refusal, a crash between the two commits,
     address full) are joined by a new kill-mid-batch test.
7. **`system/artemis`**: brokerconfig, setupreview rule packs.
8. **Neutral data model.** Edit the baselines in place; after a changeset rename, clean `target/classes`.
9. **Lock-down**: ArchUnit, `PluginValidator`, the `Contract.VERSION` bump, ADRs (SDK, reactor, the
   roles model, driver SPI, capability model), docs, and the roadmap update.

## Verification (finish line)

- The TCK is green against Artemis in the driver's integration tests.
- The golden files are byte-identical after extraction: same requests, same batch counts per node.
- The existing suite passes; CI runs the full suite. Local checks are narrow, inside the capped
  `systemd-run` scope, at most two at once.
- ArchUnit and Modulith are green. `grep -ril "org.apache.activemq\|jolokia" rahdar/src` shows hits only
  in `system/artemis`, through `unwrap`.
- `just demo` works end to end, including HA.
- The Sonar quality gate is green; the PR merges on green CI.

## Risks / Trade-offs

- **Behaviour drift during extraction.** Mitigation: golden files of the Jolokia traffic are recorded
  before anything moves, and must match byte for byte afterwards.
- **Relay correctness** (transacted batch, duplicate refusal, two commits). Mitigation: it moves last,
  behind the existing relay ITs plus a kill-mid-batch test.
- **Closed sets frozen at `1.0`** (`Role`, `Operation`, `Position`, `Status`, `Plane`). Mitigation: they
  are completed against all four systems now. The Kafka and RabbitMQ drivers run before Rahdar's stable
  release, while breaking changes are still free.
- **A larger build** (a reactor, five SDK artifacts). Accepted: it is what makes the SDK adoptable and
  lets the compiler enforce the dependency direction.
