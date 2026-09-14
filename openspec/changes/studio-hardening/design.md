## Context

See proposal.md for the motivation. The current state, as read in source (paths relative to
`src/main/java/io/github/sudoitir/artemisstudio/`):

- **Jolokia clients.**
  - `platform/broker/BrokerClientFactory.forNode` builds a `ClientHttpRequestFactory` per
    call via `ClientHttpRequestFactoryBuilder.detect()`.
  - The only HTTP client on the classpath is the JDK one, so every Jolokia call creates a
    `java.net.http.HttpClient` (a SelectorManager thread and an epoll fd), freed only by GC.
  - `BrokerConnections.forCluster` is called for every call by ~20 callers.
- **Limiter.**
  - `NodeCallLimiter` is a per-node semaphore refilled once a second.
  - Callers call `acquire(nodeId)` once per logical operation, then issue any number of
    requests.
  - Some paths never acquire: `ClusterService` registration and rediscovery,
    `CapabilityProbe`, `RrSampler` (by design, Core).
- **Core browse.** `CoreMessageTransport.browse` enumerates the whole queue to compute
  `total`. `MessageOperations` by-id loops send one exec per id.
- **Capture pipeline.**
  - The flow is `CaptureConsumer.Drain` (CLIENT_ACK listener) → `CaptureBus.publish` →
    `CaptureIndexSink` (one buffer shared by every drain, writes of 50) →
    `MessageIndexWriter.capturedBatch`.
  - `CaptureBus.flush` swallows write exceptions, then the drain acknowledges.
  - Captured rows store the source address in both `address` and `queue_name`. Footprint,
    loss, delete and retention match `queue_name` against *queue* names from the snapshot
    cache.
  - `ix_message_index_lookup (cluster_id, queue_name, observed_at DESC)` already exists.
  - `MessageIndexWriter` de-duplicates redeliveries with `NOT EXISTS` over a 10-minute window
    keyed on receipt time.
- **Reconciler.**
  - `CaptureReconciler` skips a node when `consumers.isDraining(...)`, without consulting the
    broker's actual divert list.
  - `CaptureTap.remove` deletes the shared `artemis-studio.capture.#` settings.
  - `CaptureProperties.brokerRole` defaults to `amq`.
- **Sampled tails.** A sampled index tail (`MessageIndexCapture` → `SqlTailPoller`, started
  with `fromBeginning=true`) indexes the backlog on its first poll, up to `scan-cap`
  (50,000) in one tick.
- **Divert create.** `QueueLifecycleService.createDivert` → `BrokerCommands.run` returns
  `APPLIED` straight after the exec. Artemis answers 200 and only logs when it declines to
  deploy a divert. `CaptureTap.install` already re-lists diverts to verify; the divert path
  does not.
- **Audit.** `AuditService.begin` is a plain save inside the caller's `@Transactional`, so
  the pending row commits only with the whole fan-out.
- **Shutdown.** Phases are STREAM → BROKER_CALLS → SUBSCRIPTIONS → CORE_POOL. The job
  scheduler has no shutdown step.

## Goals / Non-Goals

**Goals:**
- Studio's threads, descriptors and broker requests are bounded by configuration, never by
  elapsed time or call volume.
- No acknowledged-but-unstored captured message.
- Every loss path is counted with its cause.
- Divert and capture outcomes reflect broker reality.
- Each phase ships independently and leaves the product working.

**Non-Goals:**
- A global (cross-node) request ceiling. The node is the unit a broker is protected at.
- Unbounded capture retention during a database outage. Capture is a bounded copy of
  production traffic by design (ADR-0062). This change makes loss honest, not impossible.
- Re-architecting the capture pipeline onto a different transport or COPY-based ingest.
  Revisit only if the soak shows the batch insert is the bottleneck.
- Restarting failed notification subscriptions (`CoreEventClient` drain failure leaves a
  failed client in `active`). This is a separate bug, filed separately.
- Retrofitting UI rules to screens this change does not touch.

## Decisions

### D1 — One JDK HTTP client per TLS bundle
**Chosen.** `BrokerClientFactory` holds a `volatile Map<String, Transport>`, keyed by the TLS
bundle name (`""` for plain), where `Transport(HttpClient, JdkClientHttpRequestFactory)`.
- **Building.** Boot's `JdkHttpClientBuilder.build(HttpClientSettings)` applies the connect
  timeout, the SSL bundle and `DONT_FOLLOW`. The read timeout is set on the request factory.
- **Timeout change.** The map is swapped and the old clients get `shutdown()`, which is
  graceful for in-flight requests.
- **Certificate reload.** `SslBundles.addBundleUpdateHandler` evicts that bundle's client.
- **Shutdown.** `DisposableBean` shuts every client down.
- **Credentials and `RestClient`.** Credentials stay a per-`RestClient` interceptor, and the
  `RestClient` is still built per call: cheap, and it holds no thread.

**Alternatives.**
- Cache per `(cluster, url)`: unbounded under credential churn, with no benefit, since
  credentials don't live on the HTTP client.
- Keep `detect()` but cache its factory: `detect()` hides the `HttpClient`, so it cannot be
  shut down.
- Switch to Apache HttpClient 5: a new dependency, needing an ADR for no gain.

This restores ADR-0010 ("one RestClient per cluster"), so no new ADR is needed. The javadoc
claiming per-call builds is corrected. `setTimeouts` currently rebuilds from `defaults()`
and drops `DONT_FOLLOW`; this is fixed in passing.

### D2 — The limiter permit is per HTTP request, taken inside the client (ADR-0076)
**Chosen.** `JolokiaBrokerClient` acquires from `NodeCallLimiter` before every POST: one
permit per 50 operations, rounded up. Every existing `limiter.acquire` at call sites is
deleted.
- **Key.** Permits are keyed by the Jolokia URL, which identifies a node. A registration
  probe to a not-yet-registered URL is limited too.
- **Timeout.** A permit timeout throws `BrokerConnectionException(RATE_LIMITED)` and
  restores the interrupt flag.
- **Metrics.** `studio.broker.requests{node}` (counter) and
  `studio.broker.permit.wait{node}` (timer).

**Alternatives.**
- Add `acquire` at each bypassing call site: the bypass list grows with every new feature,
  which is exactly how the current gaps appeared.
- Limit per logical operation: this is the current design, and it lets one permit carry
  1,000 requests.

The new ADR supersedes the limiter-placement text of ADR-0025.

### D3 — Capture acknowledgement and backpressure (ADR-0077)
**Chosen.**
- **Per-drain buffers.** Each `Drain` owns its batch. `CaptureBus.publish` returns
  `Accepted | RateLimited`. The index write is called by the drain, synchronously, and
  throws on failure. Other bus listeners (console tails) stay best-effort and isolated.
- **Acknowledge only after commit.** `acknowledge()` acknowledges only after the write
  commits.
- **On a write failure:**
  1. no acknowledgement;
  2. `session.recover()`;
  3. stop the drain's connection flow (`connection.stop()`, or close the consumer), resuming
     after `Backoff` (1s to 5m, with jitter);
  4. mark the node `DEGRADED` with cause `STORE_UNAVAILABLE`.

  While paused, messages accumulate in the capture ring, which is bounded by count and
  bytes (D6). The broker's DROP policy discards the oldest, and `CaptureLoss` counts them.
- **Redeliveries.** Duplicates only arise when a write committed but its acknowledgement did
  not. They are redelivered within seconds, well inside `MessageIndexWriter`'s 10-minute
  window. A long outage produces no duplicates, because nothing was stored.
- **Unreadable message.** `recover()`. After 3 consecutive failures on the same
  `JMSMessageID`, count it as loss (cause `UNREADABLE`) and acknowledge it. This stops a
  poison message stalling the tap forever.
- **Rate cap.** A rejection increments the node's loss counter (cause `RATE_LIMIT`).
- **Close.** `consumer.close()` first, which waits for `onMessage` to finish (JMS 2.0
  §8.7), then write the batch and acknowledge, then close the session.

**Alternatives.**
- Durable capture queue with paging: breaks non-negotiable #1 (a broker can grow and page).
- Buffer in Studio memory during an outage: unbounded, and lost on restart.
- Acknowledge first and store in a local disk spool: a second store to manage; rejected as
  a stopgap.

### D4 — Audit row commits before the broker call (ADR-0078; approved)
**Chosen.** `AuditService.begin` and `finish`/`fail` run with `Propagation.REQUIRES_NEW`.
- **No fan-out transaction.** The fan-out paths (`BrokerCommands.run`,
  `QueueLifecycleService`, `MessageService` mutations) stop being `@Transactional` around
  broker calls. Any local state change after the broker call runs in its own short
  transaction.
- **Partial by-id results.** The outcome records `affected` (partial count) with the error.
- **Invalid filter.** An invalid-filter `IllegalArgumentException` now finishes the row as
  `FAILED` instead of leaving it pending.
- **Wording.** `CLAUDE.md` non-negotiable #3 is reworded to "committed before the broker
  call, updated with the outcome".

**Alternatives.**
- Keep the same transaction: a crash mid-fan-out loses the record of brokers already
  changed, and a DB connection is held across N × read-timeout.
- Outbox: heavier, and adds nothing for a row that must exist *before* the call.

### D5 — Per-instance capture objects and a reserved prefix (ADR-0079)
**Chosen.**
- **Settings match.** `CaptureNames.MATCH` becomes `artemis-studio.capture.<instanceId>.#`.
  Names already embed the instance id, so only the match changes.
- **Legacy match.** Removed at startup reconciliation only when no capture divert from any
  instance remains on the node.
- **Reserved prefix.** `CAPTURE_DIVERT_PREFIX` is reserved: `QueueLifecycleService`
  refuses create and delete (which covers REST and MCP), `addressesFor` excludes it, and
  `validPattern` rejects a pattern that only matches it.
- **Broker role.** `CaptureProperties.brokerRole` loses its default, so an unconfigured
  role refuses capture with the property name. `amq` logs a warning and is shown on the
  subscription.

### D6 — Capture correctness details
- **Footprint, loss, retention, delete.** They match captured rows with
  `queue_name IN (addressesFor(subscription))`, using the same resolver as the reconciler.
  Captured rows store the address there, and the existing lookup index serves the query.
  No migration.
- **Byte bound.** The capture address settings add `maxSizeBytes` from the new
  `artemis-studio.capture.max-ring-bytes` (default 64 MiB). A changeset lowers the
  ring-size `CHECK` ceiling to 1,000,000. Existing rows above it are clamped in the same
  changeset, with an audit-free data fix recorded in the changeset comment.
- **Reconciler reinstall.** A tap is reinstalled when it is not draining or its divert is
  missing from `actual`.
  - Drains on non-serving nodes are stopped.
  - `CaptureConsumer` registers an `ExceptionListener` on the drain's connection that
    removes the drain and marks the node `PENDING`.
  - A reinstall records the coverage gap (ADR-0066).
- **Bounds edits.** Changing `filterString`, `ringSize`, `bodyCapBytes` or `maxRate` sets
  `reinstall_requested` on the subscription's node rows. The next pass stops the drains,
  removes the taps and installs fresh ones. The audit row carries old and new values.
- **Permanent failures.**
  - `coreUrl` is checked before `tap.install`.
  - `ManagementRefusal` ARGUMENT, a missing role and a missing Core URL go to `FAILED` with
    a `failed_fingerprint` (a hash of subscription bounds + node endpoint).
  - A `FAILED` node is retried only when the fingerprint changes.
  - Audit rows are written only on state transitions.
- **Loss accounting.**
  - Routed count per address comes from the address-level counter (Artemis
    `AddressControl` `RoutedMessageCount`, read in the tier batch).
  - When a filter is set, the estimate is `null` with reason `FILTERED`.
  - `DEGRADED` clears after one clean interval, and loss is kept as
    `(window_start, dropped)`.
- **Footprint counters.** `message_capture_node` gains `captured_rows bigint` and
  `captured_bytes bigint`, placed by the column-order rule (8-byte types first) with a
  `fillfactor` for HOT updates.
  - They are incremented in the same transaction as `capturedBatch`.
  - Retention and delete decrement or reset them.
  - They replace the per-pass `count(*)` / `sum(octet_length)` scans.
- **Delete ordering.** Delete and disable stop every drain of the subscription
  (`consumers.stop`), then delete rows, then reconcile under `clusterLock.runIfHeld`.
- **Partition maintenance.** The move-from-default and ATTACH run in one transaction.

### D7 — Bounded broker reads
- **Core browse.** Stop enumerating at `skip + size`.
  - The total is one Jolokia call through the limiter: `MessageCount` without a filter,
    `countMessages(filter)` with one.
  - On failure the total is `null` with a reason.
  - `BrowsePage.total` becomes `Long` (nullable) with `totalUnavailableReason`.
- **Request-reply sampler.** `RrSampler` uses `sample(target, limit)`, which never counts.
- **By-id operations.**
  - Chunks of 50 exec operations per Jolokia batch POST (the limiter charges 1 permit per
    chunk).
  - They return `BulkResult(affected, notAttempted, error)`.
  - Whether a filter over message ids can replace the chunks is checked with ctx7 during
    apply. If it can, use one filter exec per chunk; the contract is unchanged.
- **List views.** `PagedListService` gets a single-flight cache keyed by
  `(cluster, node, kind)` with a 2s TTL (Caffeine is already managed by Boot; *verify* it
  is on the classpath, else a `ConcurrentHashMap<Key, CompletableFuture>` with an expiry
  timestamp).
- **Sampled tails.** A tail in backlog mode reads at most `backlog-pages-per-tick` (default
  5) pages per node per tick, persisting its position in the mark, and reports
  `backlogInProgress` until caught up.
- **Core client limits.**
  - `CoreConnectionFactory` sets `consumerWindowSize` (browsers 64 KiB; capture one ack
    batch).
  - `CorePool` gives capture drains a separate pool key suffix `|capture`, sized to the
    tap ceiling.

### D8 — Shutdown
- **New `JOBS` phase.** A phase between STREAM and BROKER_CALLS stops `JobScheduler`
  triggers and waits (bounded) for running jobs.
- **Buffered writes.** `BrokerEventWriter` gets a final flush step in that phase and
  re-offers a failed batch to the head of its buffer. Overflow is counted as the existing
  drop.
- **Capture and executors.** Capture drains close in SUBSCRIPTIONS, with the D3 close
  order. `SqlTailPoller.polls` is closed in the same phase.

### D9 — UI
- **Divert dialog.**
  - `@mantine/form` with blur validation mirroring the server's rules.
  - The preview snapshots the body, and inputs are read-only while it exists; "Edit" drops
    the snapshot.
  - The modal's `closeOnEscape` and `closeOnClickOutside` are off while pending.
  - Server 400 `errors[]` are mapped to fields.
  - The ownership badge becomes a button that opens `BrokerXmlRemedy`.
- **Capture.**
  - `POST /sql/index?dryRun=true` returns `{nodes, addresses, ringMessages, ringBytes,
    brokerObjects, brokerXml}`.
  - Arming uses `ConfirmByTyping` on the pattern.
  - Bounds sit behind a disclosure.
  - Copy is mode-aware.
  - Loss and footprint render "unavailable — reason" when `null`.
- **Shared.**
  - `NodeOutcomeSummary` gets `role="status" aria-live="polite"`, and blocking errors get
    `role="alert"`.
  - The browse view renders an unavailable total.
  - `schema.d.ts` is regenerated.
- **Server validation.**
  - `CreateDivertRequest`: `@Pattern` routing type, `@Size(max=200)`, name regex
    `^[^,=:*?"\\\s]+$`, and a class-level `@DistinctAddresses`.
  - `SqlIndexController` request records get `@Valid` bounds, and clamping is removed.
- **XML snippets.** `BrokerXmlSnippets` switches to the StAX writer used by `BrokerXmlCodec`.

## Risks / Trade-offs

- **The per-request limiter slows multi-step commands** (a capture install is ~10
  requests, so ~0.5s at 20/s). → Accepted. That is the ceiling doing its job. Commands
  already run asynchronously and report per node.
- **`session.recover()` in a tight failure loop hammers the broker with redeliveries.** →
  The drain pauses its connection flow with backoff before recovering, so redelivery waits
  for the retry.
- **`REQUIRES_NEW` audit uses a second pooled connection while an outer transaction is
  open.** → The fan-out no longer holds an outer transaction, and the remaining outer
  transactions are short. Hikari pool exhaustion is covered by a test that runs a fan-out
  under the default pool size.
- **Lowering the ring ceiling can clamp existing subscriptions.** → The changeset clamps
  and the upgrade note says so. The byte bound makes a large count ceiling meaningless
  anyway.
- **A required `brokerRole` stops existing captures after upgrade.** → They go to `FAILED`
  with the property named. The migration step is in the `fix(capture)!` commit body.
  Already-installed taps keep draining until the next reinstall.
- **The Core browse total now costs one Jolokia call, where it used to walk the queue.** →
  One bounded call, versus a full queue walk, is strictly cheaper. It is counted by the
  limiter.
- **Divert cycle detection depends on Artemis behaviour for re-diverted copies.** → Measure
  on the dev stack (Artemis 2.56) during apply and record the result in ADR-0079's notes.
  The refusal stands either way, because a cycle is never intentional configuration.
- **`AddressControl.RoutedMessageCount` availability varies by version.** → Verify with
  ctx7. If it is absent, loss reports unavailable rather than a wrong number.

## Migration Plan

1. Phases ship in order: 1 (leak + heap default) → 2 (data safety + audit) → 3 (broker
   pressure) → 4 (capture correctness, breaking) → 5 (divert) → 6 (UI). Each is one commit
   on the feature branch; the PR merges to `main` and releases once.
2. **Upgrade notes** (commit bodies):
   - `JAVA_OPTS` pinned to `MaxRAMPercentage=75` should be reviewed.
   - Set `ARTEMIS_STUDIO_CAPTURE_BROKER_ROLE` before upgrading if capture is used.
   - Ring sizes above 1,000,000 are clamped.
   - The browse `total` may be `null`.
   - By-id operations can return partial.
3. **Liquibase.** New changesets only, under `db/changelog/feature/sql/changes/`. No
   released changeset is edited.
4. **Rollback.** Revert the release image. The new columns are additive, and the lowered
   `CHECK` is compatible with the previous code. Per-instance capture settings left on a
   broker by the new version are removed by the old version's orphan sweep only if their
   diverts are gone; otherwise remove them with the documented `broker.xml`/Jolokia step.
