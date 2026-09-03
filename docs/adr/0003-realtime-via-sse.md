# ADR-0003: Real-time updates over Server-Sent Events

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi

## Context

The UI needs live updates for topology, queue counters, alerts, and request-reply
flows. Every one of these is server→client. Commands (purge, move, send) are
ordinary request/response.

## Decision

Use **SSE**. One endpoint, `GET /api/v1/stream?clusterId=&topics=...`,
multiplexing named event types. The browser's `EventSource` handles reconnection.
TanStack Query cache entries are patched from the stream so components stay
declarative. If `EventSource` fails twice, the client degrades to polling
automatically.

## Consequences

- Minimal server code: one `Flux<ServerSentEvent>` per subscriber.
- No STOMP, no socket lifecycle, no heartbeat protocol to hand-roll.
- Proxies must not buffer the stream (`X-Accel-Buffering: no`, documented for
  deployment).
- If a future feature needs client→server streaming (it does not today), it gets
  a WebSocket for that feature alone — SSE is not on the critical path for it.

## Alternatives considered

- **WebSocket** — full duplex we would use at ~5%, worse behaviour through
  corporate proxies, and a reconnect/heartbeat protocol to own.
- **Polling only** — trivial but wasteful at the cadence operators expect, and
  multiplied by every open tab.
