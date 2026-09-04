# ADR-0028: `broker_event` persistence — buffered batch insert, bounded queue with a visible drop counter

- **Status**: accepted
- **Date**: 2026-09-04
- **Deciders**: Mahdi Amirabdollahi

## Context

Phase 4 normalises every `activemq.notifications` message to a `BrokerEvent` and
needs a queryable history: for the events screen, and for a browser that
reconnects after a drop (ADR-0027). Volume is driven by broker chatter Studio
does not control — a reconnect storm can be thousands of events in a second.

`007-request-reply.sql` already has `rr_event`, but it is flow-scoped
(`FK → rr_flow`), correlated, and Phase 5. A raw notification log is a different
grain, lifetime, and reader.

## Decision

- **A new table, `broker_event` (changeset `010-broker-events.sql`).** Columns
  alignment-ordered per the schema convention. The primary key is
  `seq BIGINT GENERATED ALWAYS AS IDENTITY`, **not** a uuid: this is a log,
  insertion order *is* the identity, and a monotonic bigint doubles as the SSE
  `Last-Event-ID` cursor for free (replay is `WHERE seq > ?`). The
  uuid-PK / not-first-column convention is for entities; an append-only stream
  keyed by insertion order is the documented exception. Storage parameters match
  `rr_event` (insert-tuned, `fillfactor = 100`).
- **`props JSONB`** holds every `_AMQ_*` property verbatim; the promoted columns
  (`type`, `address`, `consumer_name`, …) are what the API filters on.
- **`BrokerEventWriter` is a buffered batch writer.** Notifications arrive on the
  Core client's drain thread; `accept` enqueues without blocking. A scheduled
  `flush` (`artemis-studio.events.flush`, default 1s) drains up to 500 and does
  one JDBC batch `INSERT` — the same rationale as `QueueSnapshotUpsert`
  (ADR-0016), a scoped exception to ADR-0011.
- **The buffer is bounded and overflow is visible.** Past
  `artemis-studio.events.buffer-size` (default 10 000, runtime-overridable),
  `accept` drops the event and increments a per-cluster counter that every
  `GET /clusters/{id}/events` response carries as `dropped`. A drop is never
  silent (non-negotiable #1).
- **`BrokerEventReaper` trims hourly** past
  `artemis-studio.events.retention-hours` (default 72, runtime-overridable),
  mirroring `MetricSampleReaper`.
- **JPA reads only.** `BrokerEventEntity` exists for the history API and
  `ddl-auto=validate`; the writer goes around it.

## Consequences

- History survives a restart and is queryable by Phase 5, without a
  cross-instance mechanism.
- A `seq`-PK table departs from the schema convention; this ADR is the record.
- The default retention (72h) is a guess against unknown broker volume; the
  first load test decides whether it holds. It is a `studio_setting`, so an
  operator can change it without a restart.
- `EventStreamPublisher` (ADR-0027) is wired to the writer through an
  `ObjectProvider<BrokerEventPublisher>` so slice 2 (persistence) shipped and was
  useful before slice 3 (the SSE topic) existed.

## Alternatives considered

- **Widen `rr_event` with a nullable `flow_id`** — couples two unrelated
  lifecycles and breaks its `ON DELETE CASCADE`.
- **Synchronous insert per notification** — couples broker chatter to DB
  latency and lets a chatty broker stall the Core consumer.
- **uuid PK + a separate sequence column** — two identifiers for one ordering.
