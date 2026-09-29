## Why

Queues and messages can be linked to directly (`?queue=`, `?message=`). The Events and Audit views cannot: there is no read of a single broker or audit event by id, so a shared link can only name a filter, and the event an operator wants to point a colleague at is usually not on the loaded page.

## What Changes

- `GET /api/v1/clusters/{clusterId}/events/{seq}` and `GET /api/v1/clusters/{clusterId}/audit/{id}` return one event. Each is gated like its list (`cluster:read` on the cluster) and answers 404 for an id that does not exist on that cluster, including one reaped by retention.
- `?event=<id>` on the Events view and on the Audit view opens that event's details, fetching it by id when it is not on the loaded page. An unknown id says the event no longer exists (retention may have removed it) instead of showing nothing.
- Each row of the two grids gets an Actions menu with a "Copy link" item that copies the address of that event's details.

## Capabilities

### Modified Capabilities
- `broker-events`: a single event is readable by id; the events screen opens and links to one.
- `audit-log`: a single audit event is readable by id; the audit screen opens and links to one.

## Impact

- **Backend**: `EventController`, `BrokerEventService`, `BrokerEventRepository`; `AuditController`, `AuditQueryService`, `AuditEventRepository`. No new module dependency.
- **API**: two new read endpoints; `web/openapi.json` and `schema.d.ts` regenerate.
- **Frontend**: `features/events` and `features/audit` (route search, fetch-by-id hook, details drawer, row menu).
