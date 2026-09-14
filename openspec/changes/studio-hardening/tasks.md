## 1. Phase 1: Thread and FD leak, heap default

- [x] 1.1 Write `BrokerClientFactoryTest`. Warm up 20 `forNode` calls, then make 200 more, and assert the live thread count grows by less than 10, interleaving `setTimeouts`. Run it and confirm it fails on current code.
- [x] 1.2 Verify with ctx7 or the Boot 4.1.1 source that `JdkHttpClientBuilder` plus `JdkClientHttpRequestFactory.setReadTimeout` reproduce `JdkClientHttpRequestFactoryBuilder` (connect timeout, SSL bundle, `DONT_FOLLOW`).
- [x] 1.3 Rework `BrokerClientFactory` to hold one transport per TLS bundle.
  - Swap and `shutdown()` the old clients in `setTimeouts`, keeping `DONT_FOLLOW`.
  - Evict a bundle's client on `addBundleUpdateHandler`.
  - `DisposableBean.destroy()` shuts every client down.
  - Correct the javadoc.
- [x] 1.4 Add a test that a redirect is still not followed after `setTimeouts`.
- [x] 1.5 `CoreSubscriptionManager.start`: close the client, and so its factory, when start fails. Add a test that a failed `createConnection` closes the factory.
- [x] 1.6 Set `MaxRAMPercentage=50` in `Dockerfile`, `deploy/compose/.env.example` and `deploy/compose/compose.prod.yaml`, and update any docs mentioning 75.
- [ ] 1.7 Run `./mvnw test -Dtest='BrokerClientFactoryTest,CoreSubscriptionManagerTest'` and `just fmt`. Commit `fix(broker)` and `fix(deploy)` with operator-facing bodies.

## 2. Phase 2: Data safety and audit durability

- [ ] 2.1 Write ADR-0077 (capture acknowledgement and backpressure) and ADR-0078 (audit row in its own transaction). Reword CLAUDE.md non-negotiable #3.
- [ ] 2.2 Write failing tests first.
  - A capture drain against Testcontainers Artemis with a writer that fails N times: after recovery, stored rows equal messages routed, with no duplicates.
  - Two concurrent drains with one slow writer: neither drain acknowledges before its own rows commit.
- [ ] 2.3 Replace `CaptureIndexSink`'s shared buffer with per-drain batches.
  - `CaptureBus.publish` returns accepted or rate-limited.
  - The index write throws on failure; console-tail listeners stay isolated.
- [ ] 2.4 Make `CaptureConsumer.Drain` acknowledge only after commit. On a write failure:
  - do not acknowledge;
  - call `session.recover()`;
  - pause the drain's flow with `Backoff`;
  - mark the node DEGRADED (`STORE_UNAVAILABLE`);
  - resume on success.
- [ ] 2.5 Handle unreadable messages: `recover()`, then after 3 failures on the same message id count it as loss (`UNREADABLE`) and acknowledge it. Add a poison-message test.
- [ ] 2.6 Count rate-cap rejections as loss (`RATE_LIMIT`).
- [ ] 2.7 Fix `Drain.close()` ordering: close the consumer, then write the batch, then acknowledge, then close the session.
- [ ] 2.8 `AuditService.begin`/`finish`/`fail` run in `REQUIRES_NEW`. Add a test that the pending row is committed before the broker call, using a broker stub that reads the audit table during the call.
- [ ] 2.9 Remove `@Transactional` around broker fan-out in `BrokerCommands.run`, `QueueLifecycleService` and `MessageService` mutations. Keep local state writes in short transactions, and record an invalid filter as FAILED.
- [ ] 2.10 Give `MessageOperations` by-id operations a partial-result contract: `(affected, notAttempted, error)`, with `affected` recorded on the audit failure. Add a test.
- [ ] 2.11 `BrokerEventWriter`: re-queue a failed batch at the head of the buffer, counting overflow as a drop. Add a test for a DB failure.
- [ ] 2.12 Add a `JOBS` shutdown phase before `BROKER_CALLS`.
  - It stops `JobScheduler` triggers and waits a bounded time for running jobs.
  - It flushes `BrokerEventWriter` a final time.
  - `SqlTailPoller.polls` is closed.
  - Extend `ShutdownOrderTest`.
- [ ] 2.13 Run `./mvnw verify`, then commit `fix(capture)` and `fix(audit)`.

## 3. Phase 3: Broker pressure

- [ ] 3.1 Write ADR-0076 (limiter permit per HTTP request, inside the client), superseding ADR-0025's limiter placement.
- [ ] 3.2 Write failing tests first.
  - `JolokiaBrokerClientTest`: a batch of 120 operations takes 3 permits.
  - Registration and capability probes take permits.
- [ ] 3.3 Move limiter acquisition into `JolokiaBrokerClient` (keyed by Jolokia URL, one permit per 50 operations). A timeout restores the interrupt flag and maps to a stable error.
- [ ] 3.4 Delete call-site `limiter.acquire` calls: `ScrapeScheduler`, `BrokerCommands`, `MessageService`, `BrokerQueryExecutor`, `DlqService`, `MessageVerifier`, `BrokerConfigApplyService`, `RoutingService`, `ConfigDiffService`, and any others found by grep.
- [ ] 3.5 Add Micrometer `studio.broker.requests{node}` and `studio.broker.permit.wait{node}`. Document the recommended alerts in the site guide.
- [ ] 3.6 Bound `CoreMessageTransport.browse`.
  - Stop at `skip + size`.
  - Get the total from `MessageCount` or `countMessages(filter)`, or return `null` with a reason.
  - Make `BrowsePage.total` nullable with its reason.
  - Add a Testcontainers test: page 1 of a 10k-message queue reads at most one page.
- [ ] 3.7 Give `RrSampler` a `sample(target, limit)` path that never counts.
- [ ] 3.8 Check with ctx7 whether an Artemis filter over message ids can replace the per-id exec loop. Then batch by-id operations in chunks of 50 per POST, keeping the partial contract.
- [ ] 3.9 Add a single-flight 2s cache per `(cluster, node, kind)` to `PagedListService`. Test: 10 concurrent requests make 1 broker call.
- [ ] 3.10 Bound sampled index backlog walks with `backlog-pages-per-tick` (default 5).
  - Persist the position in the tail mark.
  - Expose `backlogInProgress` on the subscription.
  - Add a test.
- [ ] 3.11 Set `consumerWindowSize` in `CoreConnectionFactory` (verify the 2.56 URL parameter via ctx7).
  - `CorePool` gets a separate `|capture` key sized to the tap ceiling.
  - Test: capture drains beyond 8 do not block a browse.
- [ ] 3.12 Run `./mvnw verify` and commit `perf(broker)`.

## 4. Phase 4: Full capture correctness

- [ ] 4.1 Write ADR-0079 (per-instance capture objects, reserved prefix, required broker role).
- [ ] 4.2 Match captured rows by `queue_name IN (addressesFor(subscription))` in `CaptureLoss`, `MessageIndexService` (footprint, `maxBytes`, delete) and `MessageIndexPartitionMaintainer` (retention). Test with a multicast address whose queues have different names.
- [ ] 4.3 Reconciler reinstalls when a tap is not draining or its divert is absent from `actual`, and stops drains on non-serving nodes. Test: a divert deleted out of band is reinstalled.
- [ ] 4.4 Add an `ExceptionListener` on each capture drain connection that removes the drain and marks the node PENDING, so the next pass reinstalls it and records the gap. Test with a broker restart.
- [ ] 4.5 Bounds and filter edits flag the subscription's nodes for reinstall, audited with old and new values. Test that a narrowed filter reaches the broker divert.
- [ ] 4.6 Scope `CaptureNames.MATCH` per instance. `CaptureTap.remove` clears only that match. Startup removes the legacy shared match only when no capture diverts remain. Test with two instance ids.
- [ ] 4.7 Add `max-ring-bytes` (default 64 MiB) as `maxSizeBytes` on the capture address settings. Add a changeset lowering the ring-size CHECK to 1,000,000 and clamping existing rows.
- [ ] 4.8 Reserve the capture prefix: exclude it in `addressesFor`, and reject patterns in `validPattern` that can only match it. Test that `#` never taps capture queues.
- [ ] 4.9 Permanent failures.
  - Check `coreUrl` before install.
  - Map ARGUMENT, a missing role and a missing Core URL to FAILED with a failed fingerprint; retry only when the fingerprint changes.
  - Audit only on state transitions.
  - Test: an invalid filter gives one FAILED audit row across several passes.
- [ ] 4.10 `CaptureProperties.brokerRole`: remove the default, refuse capture with the property name when it is unset, and warn when it is `amq`.
- [ ] 4.11 Loss accounting.
  - Use the address-level routed count (verify the `AddressControl` attribute via ctx7, and fall back to unavailable).
  - Report `null` with reason `FILTERED` when a filter is set.
  - Clear DEGRADED after a clean interval, and record a loss window.
- [ ] 4.12 Changeset adding `captured_rows` and `captured_bytes` to `message_capture_node` (column order per non-negotiable #7, fillfactor).
  - Increment them in the `capturedBatch` transaction; retention and delete decrement them.
  - Replace the per-pass `count(*)`/`sum` scans.
- [ ] 4.13 Delete and disable stop every drain of the subscription before deleting rows. `SqlIndexController` reconciles under `clusterLock.runIfHeld`. Test that no rows remain after deleting while messages flow.
- [ ] 4.14 Run the partition move-from-default and ATTACH in one transaction.
- [ ] 4.15 Add `POST /sql/index?dryRun=true` returning nodes, addresses, ring bounds, broker objects and `brokerXml`, without saving or touching a broker. Bean-validate request bounds, returning 400 instead of clamping, and include every bound in the audit params.
- [ ] 4.16 Run `./mvnw verify` and commit `fix(capture)!` with the migration steps in the body (broker role required, ring clamp).

## 5. Phase 5: Divert correctness

- [ ] 5.1 Extract divert deployment verification from `CaptureTap` into `DivertOperations`.
  - `createDivert` compares against `listDiverts` after the request.
  - The result is APPLIED, ALREADY, or FAILED with the differing fields, or FAILED as not deployed.
- [ ] 5.2 Preflight shared by the dry run and the real request.
  - Refuse source equal to forwarding.
  - Refuse a forwarding address that neither exists nor is auto-created, with the `broker.xml` remedy.
  - Refuse a divert cycle, naming it; first measure Artemis 2.56 behaviour on the dev stack and note it in ADR-0079.
  - Warn about an exclusive divert on a captured address, requiring `acknowledgeCaptureShadowing`.
- [ ] 5.3 Refuse capture-prefixed names in create and delete at the service, so REST and MCP are both covered.
- [ ] 5.4 `CreateDivertRequest` validation: `@Pattern` routing type, `@Size(max=200)`, name regex, and a class-level distinct-addresses check. Return 400 with per-field errors, reusing the `BrokerConfigValidator` rules.
- [ ] 5.5 `BrokerXmlSnippets` emits XML via the StAX writer. Test with `<`, `&` and `"` in the filter.
- [ ] 5.6 `RoutingService.operatorOwnedDivertNames` makes a bounded query (latest event per name) instead of loading the full history.
- [ ] 5.7 Integration tests on Testcontainers Artemis:
  - duplicate name gives ALREADY or FAILED;
  - a missing forwarding address is refused;
  - A→B then B→A is refused;
  - a capture-prefix name is refused over REST and MCP.
- [ ] 5.8 Run `./mvnw verify` and commit `fix(routing)`.

## 6. Phase 6: UI guards

- [ ] 6.1 Load the `frontend-development-guide` skill, then read `theme.css` and the nearest screens. Regenerate `kernel/api/schema.d.ts`.
- [ ] 6.2 `ui/NodeOutcomeSummary`: `role="status" aria-live="polite"`; blocking error alerts get `role="alert"`.
- [ ] 6.3 `DivertActions` form.
  - `@mantine/form` blur validation mirroring the server rules.
  - Server 400 errors mapped to fields.
  - Autofocus the first invalid field.
  - A disabled Preview states its reason.
- [ ] 6.4 `DivertActions` preview and outcomes.
  - The previewed body is frozen, with read-only inputs and an Edit action.
  - The modal cannot close while pending.
  - Four outcomes with per-node detail.
  - Preflight warnings and the acknowledgement checkbox for capture shadowing.
- [ ] 6.5 `RoutingView`: the ownership badge becomes a keyboard-reachable button that opens `BrokerXmlRemedy`.
- [ ] 6.6 `IndexSubscriptions` arming.
  - Capture dry-run preview (nodes, addresses, bounds, objects, `BrokerXmlRemedy`).
  - `ConfirmByTyping` on the pattern to arm.
  - Bounds behind a disclosure, validated on blur, with server refusals shown.
- [ ] 6.7 `IndexSubscriptions` states.
  - Mode-aware copy.
  - Per-node PENDING/ACTIVE/DEGRADED (with cause)/FAILED (with remedy).
  - Loss and footprint "unavailable — reason".
  - Backlog in progress.
  - Delete copy for unreachable nodes, and the per-node removal outcome.
- [ ] 6.8 Message views render the partial by-id outcome and an unavailable browse total.
- [ ] 6.9 Vitest tests querying by role and name. Keyboard-only pass for create divert, arm capture and delete capture: focus enters the dialog, Escape dismisses it (not while pending), and focus returns to the trigger.
- [ ] 6.10 Run `just verify` and commit `feat(ui)`.

## 7. Verification

- [ ] 7.1 Soak on `just dev-up`: 2 clusters, UI open, capture running. At t=0 and t=30m, thread count by name prefix and fd count are flat, and per-node request rate stays at or below the ceiling. Trace any growing `SimpleAsyncTaskExecutor-*` prefix.
- [ ] 7.2 Fault injection at ~200 msg/s while capturing:
  - stop Postgres for 2 minutes: routed = stored + reported loss;
  - restart the broker: capture resumes and the gap is recorded;
  - delete the capture divert over Jolokia: it is reinstalled;
  - kill Studio mid-batch: no loss, no duplicates.
- [ ] 7.3 Divert refusals and outcomes verified over UI, REST and MCP. Contrast checked in both schemes.
- [ ] 7.4 Run `openspec validate studio-hardening --strict` and `just verify` clean.
