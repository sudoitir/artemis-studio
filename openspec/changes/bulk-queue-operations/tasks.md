## 1. Decision record

- [x] 1.1 Write ADR-0093 "Bulk operations are persisted runs over the single-queue commands" (D1–D6), add it to `docs/adr/README.md`

## 2. Audit parent (kernel)

- [ ] 2.1 Changeset in `db/changelog/kernel/audit/changes/`: nullable `parent_id uuid` on `audit_event` + index; entity/repository mapping
- [ ] 2.2 `AuditScope.PARENT` `ScopedValue<UUID>`; `AuditService.begin` records it when bound; test that a bound scope links the child and an unbound one leaves it null
- [ ] 2.3 Audit API view exposes `parentId`; audit query accepts a `parentId` filter; test

## 3. Bulk schema and module

- [ ] 3.1 `feature/bulk` module: `package-info.java` (allowedDependencies per D1), `BulkModule` descriptor, `BulkFeature` (`@FeatureModule("bulk")`), registered in `app/StudioFeatures`
- [ ] 3.2 `db/changelog/feature/bulk/` changelog with `bulk_run` and `bulk_run_item` (column order, fillfactor/autovacuum on items, partial unique index on RUNNING per cluster), included from the master changelog
- [ ] 3.3 JPA entities + repositories + MapStruct mappers; `safety.bulk-queue-cap` setting (default 200)

## 4. Preview

- [ ] 4.1 Resolve the selection (explicit names or `q` filter, all pages) via `CrossNodeAggregator`; refuse over `safety.bulk-queue-cap` stating matched count and cap
- [ ] 4.2 Per-item figures, refusals and warnings from the aggregated per-node data (unknown stays null); run estimate + completeness + overCap vs `safety.bulk-cap`
- [ ] 4.3 Persist the PREVIEWED run with `plan_hash` and 10-minute expiry; daily housekeeping deletes expired previews
- [ ] 4.4 Tests: frozen set, unknown estimate stated, refused queue excluded from blast radius, queue cap refusal, hash stability

## 5. Run engine

- [ ] 5.1 Execute: check hash/expiry/not-started/permission/over-cap-without-override; flip to RUNNING (unique index → 409 naming the running run); commit parent audit event
- [ ] 5.2 `BulkRunner`: virtual thread with captured SecurityContext and audit actor; sequential items inside `ScopedValue.where(AuditScope.PARENT, …)`; pause/resume/delete/purge-per-node through the existing services; item classification; counters; SSE `bulk` frame per item
- [ ] 5.3 Failure policy (stop at first failed/partial → SKIPPED, or continue); stop flag → CANCELLED; terminal status + parent audit finish in `finally`
- [ ] 5.4 Startup recovery: RUNNING → INTERRUPTED, in-flight item UNKNOWN, pending CANCELLED, parent audit failed
- [ ] 5.5 Tests: stop-on-failure, continue, stop mid-run, partial item, revoked permission, recovery, audit parent linkage

## 6. Web API and wiring

- [ ] 6.1 `BulkController`: `POST /bulk/preview`, `POST /bulk/runs/{id}/execute`, `POST /bulk/runs/{id}/stop`, `GET /bulk/runs`, `GET /bulk/runs/{id}`; ProblemDetails for expired, hash mismatch, over cap, queue cap, already running
- [ ] 6.2 Register SSE topic `bulk` (carriesData) in the module descriptor
- [ ] 6.3 `@ApplicationModuleTest` for bulk; `ModularityTest`/`BoundaryRulesTest` green; regenerate `docs/modules/`
- [ ] 6.4 Real-broker test: bulk pause then delete over three queues, run SUCCEEDED, children linked in audit
- [ ] 6.5 Regenerate `web/src/kernel/api/schema.d.ts`

## 7. Frontend

- [ ] 7.1 Kernel slot `queues.selection` in `kernel/slots.ts`; `features/queues/QueuesView` enables selection, "select all N matching" banner, sticky selection region rendering the slot, selection resets on filter change
- [ ] 7.2 `features/bulk`: `feature.ts` (routes, nav in operations group, SSE topic handler, slot contribution), `api.ts` hooks; id added to `FEATURE_IDS` and `app/features.ts`
- [ ] 7.3 `BulkActionBar`: Pause, Resume, Purge…, Delete… gated by `useCan` (disabled with keyboard-reachable reason; offered while grants load)
- [ ] 7.4 `BulkPreviewDialog`: blast-radius sentence, unknown stated, item table with refusals/warnings and "only problems" toggle, continue-past-failures switch (off), cap override only when over cap and permitted, `ConfirmByTyping` for purge/delete (`<verb> <n> queues`), single confirm for pause/resume, busy submit, navigate to run on accept
- [ ] 7.5 `BulkRunView`: status in words, progress (reduced-motion aware), per-status counts, item table expanding to `NodeOutcomeSummary`, Stop with confirm, audit link, `aria-live` terminal announcement; live via SSE
- [ ] 7.6 `BulkRunsView` history; audit view shows parent link and children filter
- [ ] 7.7 Tests (by role): select-all-matching, preview blast radius + unknown, typed confirmation gate, keyboard pass (focus in, escape, focus returns), disabled action reason, run view announces partial outcome

## 8. Verification

- [ ] 8.1 `just verify` green (API + web)
- [ ] 8.2 README roadmap: tick "B · Bulk operations"
