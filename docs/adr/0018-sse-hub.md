# ADR-0018: The SSE hub is `SseEmitter` on Spring MVC, carrying poll-derived change signals

- **Status**: accepted (extended by [ADR-0027](0027-sse-events-topic-carries-data.md))
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi

> **Extended (Phase 4).** [ADR-0027](0027-sse-events-topic-carries-data.md) adds
> one data-bearing topic, `events`, which carries the `BrokerEvent` payload and
> an `id:` line for `Last-Event-ID` replay. The "events are change signals, not
> data" decision below stays binding for every other topic; ADR-0027 also adds
> `consumers` / `sessions` / `connections` signal topics and a coalescer for the
> notification-driven ones.

## Context

ADR-0003 chose SSE for server→client updates: one endpoint,
`GET /api/v1/stream?clusterId=&topics=...`, multiplexing named events, with the
browser's `EventSource` handling reconnection and a fallback to polling after two
failures. Its Consequences section says "one `Flux<ServerSentEvent>` per
subscriber".

That line predates ADR-0010, which removed `spring-boot-starter-webflux` and
states plainly: "SSE (Phase 2) uses `SseEmitter` on Spring MVC." Reactive types
are not to enter `broker/`, `domain/`, or the scheduler.

Phase 2 also has no push source. `docs/broker-management-notes.md` §8: Jolokia is
request/response only; `activemq.notifications` needs the Core client, which is
Phase 4. So the Phase 2 stream can only fan out state changes discovered by the
scrape loop.

## Decision

We will build the SSE hub on Spring MVC `SseEmitter`.

- **Endpoint:** `GET /api/v1/stream?clusterId={uuid}&topics={csv}` returns
  `new SseEmitter(0L)` (no timeout; a heartbeat keeps it open). Topics:
  `topology`, `health`, `queues`.
- **`sse/SseHub`** — an in-memory `Map<UUID clusterId, Set<Subscriber>>`, each
  `Subscriber` wrapping an emitter and its subscribed topic set. Registration
  and removal are the only mutations; `emitter.onCompletion` / `onTimeout` /
  `onError` all deregister.
- **Events are change signals, not data.** Payload is
  `{"topic","clusterId","ts"}`. The client refetches the matching TanStack Query
  key. This keeps the stream tiny and the components declarative (ADR-0003).
- **Emitted only on a real change.** After a tier tick the scheduler diffs the
  newly persisted state against the previous (topology node set + roles; health
  enum; the set of changed `queue_snapshot` PKs) and calls
  `hub.publish(clusterId, topic)` only when it actually changed — no event every
  tick.
- **Poll-derived only in this phase.** Push/notification events are Phase 4;
  they will feed the same hub.
- **Keep-alive:** a `:ping` comment every ~20s on every open emitter. The
  response carries `X-Accel-Buffering: no`; proxies-must-not-buffer is documented
  in `docs/architecture.md` and the compose files.
- **Client fallback:** `src/api/stream.ts` opens one `EventSource` per mounted
  cluster view; two consecutive failures ⇒ stop reconnecting and rely on the
  existing 5s TanStack Query `refetchInterval` (so "fallback to polling" is
  literally "stop streaming").
- **ADR-0003's `Flux` consequence is annotated**, not edited — a note pointing
  to ADR-0010 (mechanism) and this ADR. ADR-0003's decision (SSE, one endpoint,
  named events, two-failure fallback) is unchanged and still binding.

## Consequences

- No WebFlux, no reactive plumbing, no STOMP. One `SseEmitter` per subscriber, a
  `ConcurrentHashMap` registry, a `@Scheduled` heartbeat.
- The subscriber registry is in-memory, so it does not survive a restart and is
  not shared across instances. Matches `docs/architecture.md` ("In-memory — the
  SSE subscriber registry"); multi-instance fan-out is post-MVP.
- Clients always converge on correct state even if an event is missed: the event
  only says "refetch", and the 5s poll is the floor.
- A broker that changes nothing produces no stream traffic beyond the heartbeat.

## Alternatives considered

- **`Flux<ServerSentEvent>` (ADR-0003's original phrasing).** Requires WebFlux,
  which ADR-0010 removed. Rejected — superseded by ADR-0010.
- **Send the changed data in the event payload.** Larger events, a second
  serialization path to keep in sync with the REST DTOs, and the client would
  still need to reconcile with its query cache. Sending "refetch" is smaller and
  has one source of truth. Rejected.
- **WebSocket.** Full duplex used at ~0%, a reconnect/heartbeat protocol to own,
  worse proxy behaviour. Rejected (ADR-0003 already rejected it).
- **Emit on every tick regardless of change.** Wakes every client every 5–15s
  for nothing. Rejected in favour of a persisted-state diff.
