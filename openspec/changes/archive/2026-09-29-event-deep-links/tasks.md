## 1. Backend

- [x] 1.1 `BrokerEventService.get` and `GET /clusters/{clusterId}/events/{seq}`, guarded like `page`, 404 through `NotFoundException`; repository lookup by cluster and seq
- [x] 1.2 `AuditQueryService.get` and `GET /clusters/{clusterId}/audit/{id}`, guarded like `page`, 404 for an unknown id or another cluster's
- [x] 1.3 Controller tests: found, unknown, another cluster's, no grant (`EventControllerTest`, `AuditControllerTest`)
- [x] 1.4 Regenerate `web/openapi.json` (`OpenApiSnapshotTest`) and `schema.d.ts`

## 2. Frontend

- [x] 2.1 `event` search parameter on both routes; `useEvent` / `useAuditEvent` fetch by id, only when the event is not on the page
- [x] 2.2 Details drawer opens from the URL and closes by clearing it; an unknown id shows "no longer exists" copy
- [x] 2.3 Row menu with "Copy link" on both grids
- [x] 2.4 View tests by role: off-page open, unknown id message, copy link

## 3. Ship

- [x] 3.1 Narrow checks green, PR, merge on green CI
