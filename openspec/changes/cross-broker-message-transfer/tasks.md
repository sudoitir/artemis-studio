## 1. Per-cluster Core TLS (ADR-0098)

- [x] 1.1 `platform/broker/StudioSslContextFactory` implements Artemis `SSLContextFactory`. It is registered in `META-INF/services/org.apache.activemq.artemis.spi.core.remoting.ssl.SSLContextFactory` with a priority above the default. It resolves the `sslContext` transport parameter to a Spring SSL bundle, cached per name. For anything else it defers to the default. A static holder is set by a Spring component.
- [x] 1.2 `CoreConnectionFactory` emits `sslEnabled=true;sslContext=<bundle>` and stops calling `SSLContext.setDefault`. Remove the `ponytail:` comment.
- [x] 1.3 Test: two brokers with distinct test CAs (generated PEM bundles) are connected at the same time. An undefined bundle fails only its own cluster, with the bundle named.

## 2. Background run control (ADR-0093 extraction)

- [x] 2.1 Add `kernel/jobs/BackgroundRuns` with `start(runId, Operator, Runnable)` on a virtual thread under `OperatorHandoff.runAs`, plus `requestStop`, `stopRequested` and `isActive`. It deregisters in `finally`.
- [x] 2.2 `feature/bulk/BulkRunner` delegates to `BackgroundRuns`. Behaviour is unchanged, and the bulk test suite stays green.

## 3. Broker primitives (`platform/broker`)

- [ ] 3.1 `MessageOperations`: add the chunked `moveMessages(flushLimit, filter, queue, rejectDuplicates, count)`, `copyMessage` by id, and `countMessages` for a frozen filter. Add `FrozenFilter.compose(filter, t0)`, which handles null, blank and parenthesised filters, and give it unit tests.
- [ ] 3.2 Staging lifecycle over Jolokia: create a durable anycast queue from JSON (auto-delete off), add and remove exact-match address settings from JSON (`max-delivery-attempts=-1`, `redistribution-delay=-1`, no DLA), and destroy the queue and its address. Operations are idempotent, so they are safe to repeat on resume. List `studio.transfer.*` queues per node.
- [ ] 3.3 Acceptance facts: one batched Jolokia read per node (queue, address, address settings JSON, server disk, ID cache, consumers, persistent size), charged to the limiter. Each fact that did not answer comes back as null, so it can be reported as unknown.
- [ ] 3.4 `CoreRelay`: Core-API sessions from `CoreConnectionFactory.build(...).getServerLocator()` in a dedicated relay pool, separate from operator and capture sessions. Provide the source (manual-ack) receive from a queue, the source browse-only receive with a filter, the target transacted send to an FQQN, and commit/rollback. Closing is bounded.
- [ ] 3.5 `OutboundMessages.from(source, provenance)` (pure): body buffer and type verbatim; durable, priority, expiration, timestamp and userID; properties minus the drop set; the dup id; the provenance properties. Large messages go through a 0600 temp file, after a free-disk check. The temp directory is swept on startup.
- [ ] 3.6 Unit tests for `OutboundMessages`, one per body type (Text/Bytes/Map/Stream/Object), plus the drop set, the provenance and a large message.

## 4. Transfer module backend (`feature/transfer`)

- [ ] 4.1 Module skeleton: `package-info` (`allowedDependencies`), `TransferModule` descriptor (topic `transfer`, settings), `TransferFeature`, registration in `app/StudioFeatures`, and a `@ApplicationModuleTest`. `ModularityTest` and `BoundaryRulesTest` stay green.
- [ ] 4.2 Liquibase `db/changelog/feature/transfer/changes/0001-transfer-run.sql`: `transfer_run` (row-padding order, CHECK on state and mode, partial unique index on the active source queue, fillfactor and autovacuum) and `transfer_copied` (PK `(run_id, message_id)`), both with rollback. Add the include in the master changelog. Add entities, repositories (CAS `transition`) and a MapStruct mapper.
- [ ] 4.3 Settings `transfer.batch-size`, `messages-per-second`, `max-concurrent-runs`, `capacity-threshold-percent` and `capacity-wait`, with defaults per design D8.
- [ ] 4.4 `TargetAcceptance.evaluate(facts, selection)`, a pure function returning refuse/warn/unknown findings with words and snippets. It gets a table-driven unit test covering every spec row plus the unknowns.
- [ ] 4.5 `TransferService.preview`:
  - Resolve the source and target, check both clusters' permissions (404 for the target), capabilities and liveness.
  - Freeze `t0`, estimate the count and bytes, run acceptance, apply the cap, compute the plan hash, and save the run as PREVIEWED.
  - No broker state change.
- [ ] 4.6 `TransferService.execute`:
  - Check the plan hash, expiry, cap/override and acknowledgements.
  - Enforce the concurrency limit, claim the run (409 on the unique index), audit on the source and the child audit on the target, then start `BackgroundRuns`.
- [ ] 4.7 `TransferRunner`:
  - **Move:** create the staging queue and settings, bounded-refill staging, run relay batches (permission re-check, acceptance/capacity re-check leading to `WAITING_FOR_CAPACITY` with backoff and wait timeout, pacing, a limiter permit on both nodes), commit the target and then the source, publish SSE progress to both clusters, then finish and clean up.
  - **Same-node move:** chunked `moveMessages` straight to the target.
  - **Copy:** browse relay plus a ledger insert in the same step; ledger cleanup at the end.
  - Classify not-transferred messages (in delivery, scheduled, expired) so the run can end as PARTIAL.
  - Node resolution per batch from the `artemisNodeId`.
  - A fault seam (`TransferFaults` bean, a no-op in production) sits between the target commit and the source commit.
- [ ] 4.8 Stop, resume and return:
  - **Stop:** finish the batch in flight, then STOPPED.
  - **Resume:** from STOPPED, INTERRUPTED or FAILED.
  - **Return:** chunked `moveMessages` from staging back to the source, then clean up, then RETURNED.
  - Each is audited separately, and a run holding messages can never be deleted.
- [ ] 4.9 `TransferRecovery` on `ApplicationReadyEvent`: RUNNING and WAITING runs become INTERRUPTED, and the audit is updated. Sweep large-message temp files. Orphan detection lists `studio.transfer.*` queues that have no run.
- [ ] 4.10 Web layer `TransferController` under `/api/v1/clusters/{clusterId}/transfers`:
  - `POST /preview`, `POST /runs/{id}/execute|stop|resume|return`;
  - `GET /runs` (the cluster as source or target), `GET /runs/{id}`, `GET /orphans`, `POST /orphans/return`;
  - problem advice with stable slugs, views and OpenAPI.
  - Regenerate `web/src/kernel/api/schema.d.ts`.
- [ ] 4.11 Housekeeping cron for expired previews.

## 5. Integration tests (Testcontainers: Postgres + two Artemis brokers + a two-live-node cluster)

- [ ] 5.1 Test support:
  - A second Artemis container, registered as a second cluster, using the `deploy/compose/artemis/secondary` config or a test copy.
  - A two-live-node symmetric cluster fixture, using a new test `broker.xml` pair.
  - Helpers that produce and consume messages over Core.
- [ ] 5.2 Move by ids, by filter and by all: exact counts on both sides, and the staging queue is gone afterwards.
- [ ] 5.3 Copy by filter: the source is unchanged and the target holds exactly N; resume after an injected fault still gives exactly N.
- [ ] 5.4 Fidelity: every body type, a 5 MiB large message byte-equal, properties, priority, expiration, group and correlation id; provenance present and bookkeeping absent.
- [ ] 5.5 Crash injection between the target commit and the source commit, then resume: the target holds exactly N.
- [ ] 5.6 Target paused mid-run: FAILED with the remainder in staging. After unpause, resume completes. In a second scenario, return to source restores the exact source depth.
- [ ] 5.7 Acceptance refusals and warnings:
  - Refused: DROP, a filtered target, a backup target, a missing queue with auto-create off, a same queue on the same node.
  - FAIL with a small max-size gives `WAITING_FOR_CAPACITY`; after a drain the run resumes; after the timeout it is STOPPED.
- [ ] 5.8 Large queue (100k messages): completes, the staging depth bound is never exceeded, and the limiter is respected.
- [ ] 5.9 Frozen selection with a producer still running: the run ends and only messages up to `t0` move.
- [ ] 5.10 Forced redistribution: messages chosen by id from node 1 land on node 2's local queue.
- [ ] 5.11 Restart simulation gives INTERRUPTED, and a resume completes it. An orphaned staging queue is listed and can be returned.
- [ ] 5.12 Permissions:
  - A missing target grant returns 404.
  - A grant revoked mid-run stops the run with a cause.
  - A preview creates no queue.
  - Cap and override work.
  - A concurrent run on the same source returns 409.
  - `max-concurrent-runs` is honoured.

## 6. Frontend (`web/src/features/transfer`)

- [ ] 6.1 Add the kernel slot `messages.selection` (ids | filter | all, plus the source context) and render it in `features/messages/MessageActions` / `MessagesView`. Export `AddressPicker` from `features/queues/index.ts` and add the eslint boundary edge.
- [ ] 6.2 `feature.ts`: routes `clusters/$clusterId/transfers` and `.../transfers/$runId`, nav in the messages group, the `transfer` stream topic invalidating queries, and the slot contribution ("Transfer…" and "Redistribute to node…"). Add `api.ts` hooks and register the feature in `app/features.ts` and `FEATURE_IDS`.
- [ ] 6.3 `TransferDialog`:
  - **Destination step:** Move/Copy, a cluster Select (disabled with a reason when not permitted), a node Select (backups disabled with a reason), and `AddressPicker`.
  - **Preview step:** the blast-radius sentence, the acceptance checklist in words with snippets, acknowledgement for warnings, the cap override, and `ConfirmByTyping` of the source queue name for a move.
  - Capability gating uses `gateFor` on both clusters, and there is an aria-live preview region.
- [ ] 6.4 `TransferRunView`:
  - A pipeline strip (Selected → Held in staging → Delivered) with tabular figures, a progress bar, rate and ETA, and the state sentence in a `role="status"` region.
  - `OutcomeSummary` rows for the source and target nodes.
  - Stop, Resume, and Return (typed confirm), plus an audit link. All four outcomes are rendered.
- [ ] 6.5 `TransfersView`: a list of runs where the cluster is the source or the target, with empty and filtered-empty states and an orphaned-staging section with a return action.
- [ ] 6.6 Tests (Vitest + MSW, queried by role and name):
  - the dialog flow and the disabled-with-reason options;
  - refuse, warn and unknown rendering, and the typed-confirm gate;
  - a keyboard pass (focus enters the dialog, Escape closes it, focus returns);
  - the four outcomes on the run page, and the aria-live announcements;
  - list states.

## 7. Verification and docs

- [ ] 7.1 `just fmt`, then `./mvnw verify` (Docker) and the web lint, typecheck and tests all pass.
- [ ] 7.2 Run the stack with `just dev-up` and do a real cross-cluster move and copy in the app. Record the outcome.
- [ ] 7.3 Add a site guide page for the transfer and the required `broker.xml` security setting for `studio.transfer.#`. Tick the README roadmap item.
