# ADR-0027: The SSE `events` topic carries data, with `Last-Event-ID` replay

- **Status**: accepted
- **Date**: 2026-09-04
- **Deciders**: Mahdi Amirabdollahi

## Context

ADR-0018 built the SSE hub on the principle that **events are change signals,
not data** — `{topic,clusterId,ts}`, and the client refetches the matching
TanStack Query key. That is right for every poll-derived topic: there is a
server-side resource behind `topology` / `health` / `queues`, so "something
changed, refetch" is enough.

Phase 4's broker events are different. A live feed of "consumer X on address Y,
node Z, at T" has no query key to refetch — there is no server-side list
endpoint whose contents just changed, there is a stream of facts. And the
`consumers` / `sessions` / `connections` resource views that a notification
implies are stale are served by **live per-node broker reads**
(`PagedListService.fanOut`), so a signal per notification would be one Jolokia
call per node per notification — an uncoalesced chatty broker turning push into
a self-inflicted DoS (non-negotiable #1).

## Decision

We will **extend** ADR-0018, not edit it. Its decision — SSE, one endpoint,
named events, two-failure fallback, signal topics carry no data — stays binding
for every topic except `events`.

- **The `events` topic carries the full `BrokerEvent` payload** and sets the SSE
  `id:` line to the `broker_event.seq`. `SseHub.publish` gains a
  `publish(clusterId, topic, data, eventId)` overload; the existing
  `publish(clusterId, topic)` delegates with nulls and the signal envelope is
  untouched.
- **A reconnecting client replays what it missed.** `GET /api/v1/stream` reads
  the `Last-Event-ID` header; when present and the client wants `events`, the
  controller flushes `BrokerEventService.since(clusterId, lastId, 500)` before
  live delivery. The replay is bounded — a client gone longer than the cap or
  the retention window gets at most the 500 most-recent missed events.
- **Derived staleness is coalesced.** `TopicCoalescer` collapses the
  `consumers` / `sessions` / `connections` / `queues` signals a burst of
  notifications implies into **at most one signal per `(cluster, topic)` per
  coalescing window** (`artemis-studio.events.coalesce-window-millis`, default
  1000). The first `touch` in a window schedules the signal; later touches are
  absorbed. `StreamSignals`' signature machinery is for poll dedup and is not
  reused — push events are already edge-triggered.
- **`KNOWN_TOPICS` gains `events`, `consumers`, `sessions`, `connections`.** An
  unknown topic in the CSV is still filtered out; a request that matches nothing
  falls back to the three signal defaults (not all known topics — `events` is
  heavier and opt-in).
- The frontend `Topic` union gains the four names in the same change, because
  the controller filters unknown topics rather than rejecting them — a name that
  exists on only one side fails silently.

## Consequences

- The events screen and a reconnecting browser get history without a
  cross-instance mechanism; the `broker_event` table (ADR-0028) is the backing
  store and `seq` is both the PK and the cursor.
- One more `SseHub` overload and a coalescer bean. No WebFlux, no STOMP,
  ADR-0018's mechanism is unchanged.
- The coalescing window is a latency/throughput knob: too short and a reconnect
  storm still costs; too long and the resource views feel stale. 1s is the
  starting point, tunable at runtime.

## Alternatives considered

- **Signal-only + a paged `/events` endpoint the client refetches** — stays
  literally inside ADR-0018 but a live feed refetching a paged endpoint on every
  event is wasteful and races the live edge.
- **One signal per notification** — a 500-consumer reconnect storm becomes
  500 × (nodes) Jolokia calls.
- **Editing ADR-0018** — its "events are signals" decision is still correct for
  every other topic; superseding it wholesale would misrepresent the design.
