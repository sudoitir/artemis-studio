## Why

Nothing in Studio tells an operator whether what they are looking at is current.

There is no refresh control on any screen, no "last updated", and no connection
indicator. Fifteen query hooks poll on an interval; **seventeen do not poll at
all** — settings, request-reply expectations, alert rules, users, tokens,
environments and the rest are fetched once and then held until something
invalidates them. On those screens the data can be arbitrarily old and the page
looks identical either way.

The event stream makes it worse rather than better. `useClusterStream` stops
reconnecting after two consecutive failures and silently hands over to polling
— on a screen that does not poll, it hands over to nothing. Its keep-alive is an
SSE *comment*, which fires no `EventSource` event, so a connection a proxy has
quietly dropped is indistinguishable from an idle one: the client believes it is
live indefinitely.

The operator-facing consequence is the worst kind of wrong. This is a console
people watch during an incident. A queue depth that stopped updating five minutes
ago, rendered exactly like one that updated a second ago, is not a missing
feature — it is a screen that lies. And an operator who suspects it has no way to
force a refresh short of reloading the browser, which drops every other view's
state with it.

## What Changes

**A new capability, `data-freshness`.** One global control in the header, present
on every route, that answers three questions at all times: *is this live*, *when
did it last update*, and *update it now*.

- **State.** `Live` (the stream is connected), `Polling` (the stream is down, the
  queries are succeeding), `Reconnecting…`, `Offline` (active queries are
  failing), `Paused`. State changes are announced politely to assistive
  technology; the elapsed-time label is not, because announcing every tick is
  noise.
- **Age.** How long since the newest data on this screen arrived, as a relative
  label with the absolute local time available. Derived from the queries that are
  actually being observed, so it covers every screen without per-screen wiring.
- **Refresh.** Refetches everything the current screen is observing. Also a
  command in the palette; it takes no hotkey, because the browser owns the two an
  operator would reach for.
- **Pause.** The honest complement to a refresh button, and the direct answer to
  "I do not know whether this is updating": stop the polling, say so in the
  header, and say when new data is waiting. It does not survive a reload.

**The stream stops giving up.** It reconnects indefinitely with capped
exponential backoff and full jitter, reports its state to the UI instead of
returning nothing, and watches for silence: no frame within the watchdog window
is treated as a dead connection and forces a reconnect.

**The keep-alive becomes a named event.** A comment cannot be observed by a
client, which is exactly the property the watchdog needs. This is the only
server-side change.

## Impact

- **New capability spec**: `data-freshness`.
- **Modified spec**: `realtime-stream` — the bounded-retry requirement is replaced
  by indefinite reconnection with backoff; the keep-alive requirement gains an
  observable heartbeat.
- **New ADR-0052** — one global freshness indicator; the stream reconnects
  forever.
- Backend: `SseHub.heartbeat` emits a named event.
- Frontend: `api/stream.ts`, `api/client.ts` (a `poll()` helper at the existing
  interval sites), new `app/useFreshness.ts` and `app/FreshnessBar.tsx`,
  `app/RootLayout.tsx`, `app/ClusterLayout.tsx`, `theme.css`,
  `palette/CommandPalette.tsx`.
- No schema change. No new endpoint. No new permission.
