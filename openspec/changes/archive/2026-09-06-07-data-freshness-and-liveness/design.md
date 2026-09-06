## Context

Three facts about the current code shape this design.

1. **Every screen's data is already in one place.** TanStack Query's cache knows,
   for every query, when it last succeeded, whether it is fetching, and whether it
   is in error — and knows which queries have active observers, i.e. which ones
   the screen on the display actually depends on. Freshness can therefore be
   derived globally, with no per-screen wiring and no chance of a screen being
   forgotten.
2. **The stream is mounted in exactly one place.** `ClusterLayout` calls
   `useClusterStream`. Its state can be published from there and read by a header
   control mounted above it.
3. **Polling intervals are literals at fifteen call sites.** Pausing needs one
   seam, not fifteen edits repeated whenever a hook is added.

## Goals / Non-Goals

**Goals.** Every route states whether it is live and when it last updated. Any
route can be refreshed. The stream recovers from a transient outage without a
reload. A dropped-but-not-closed connection is detected.

**Non-Goals.** Per-panel freshness badges — one honest global answer beats twenty
competing ones. Offline queueing of mutations. Changing any polling cadence.
Server-sent full payloads: signals-then-refetch stays (ADR-0018).

## Decisions

### 1. One global indicator, not per-view badges

The alternative — a badge per card — was rejected on two grounds. It multiplies a
single fact across the screen, and it invites the reader to compare badges that
are all approximations of the same thing. One control in the header, always in the
same place, is the whole answer.

Its scope is "what this screen is observing", which is the only scope an operator
can act on. A query nobody is watching has no freshness worth reporting.

### 2. `lastUpdatedAt` is the newest, not the oldest

Two defensible readings: the newest observed `dataUpdatedAt` ("something arrived
recently") or the oldest ("everything is at least this fresh"). The oldest is
pessimistic in a misleading way — one slow background query would make a live
screen read as stale. The newest matches what the operator sees change in front of
them, and the failure states carry the honesty: a query in error puts the whole
bar into `Offline` rather than letting a fresh sibling paper over it.

### 3. The stream reconnects forever, with jitter

Giving up after two failures optimises for a server that is gone. The common case
is a proxy recycling a connection, a laptop waking, a rolling restart — all of
which recover in seconds. Capped exponential backoff (1s → 30s) with full jitter
recovers from those without a reload, and its cost when the server really is gone
is one request every 30 seconds per open tab.

Full jitter rather than fixed backoff, because a restarted Studio would otherwise
be hit by every open tab in the building simultaneously — the same
broker-friendly-by-construction reasoning applied to Studio itself.

### 4. A watchdog, and therefore an observable heartbeat

An `EventSource` does not fire `error` when a connection is dropped by an
intermediary without a FIN. The client sits on a dead socket believing it is live,
which is precisely the lie this change exists to remove. So the client treats
silence as failure: no frame for longer than the watchdog window forces a
reconnect.

That requires frames. Today's keep-alive is an SSE comment, which keeps the socket
warm and is invisible to the client. It becomes a named `ping` event: observable,
ignorable by anything that does not listen for it, and still tiny. The watchdog
window is set well above the heartbeat interval so a single missed beat is not
treated as death.

### 5. Pause is one signal, read at the interval sites

`refetchInterval` accepts a function. A module-level paused flag plus
`poll(ms)` — returning `false` while paused — converts every existing interval
into a pausable one with no per-hook state and no re-render storm.

While paused, stream invalidations still mark data stale but do not refetch
(`refetchType: 'none'`), so the bar can say *new data is available* rather than
pretending nothing happened. Pause is deliberately memory-only: a persisted pause
is a foot-gun that outlives the reason someone set it.

### 6. No green

`Live` is a bright neutral mark, matching the house rule that health is the
absence of colour (`theme.css`). Colour enters at `Reconnecting…` and `Offline`.
New tokens `--as-live`, `--as-stale`, `--as-offline` map onto the existing text
and warning tokens rather than introducing a ramp.

## Risks / Trade-offs

- **A permanently-retrying tab on a dead server.** Bounded by the 30s cap and by
  the browser suspending timers in background tabs. Cheaper than an operator
  staring at a frozen screen.
- **Changing the keep-alive from comment to event** reaches every stream
  subscriber. It is additive — a client that does not listen for `ping` ignores
  it — and the requirement change is recorded in the spec delta.
- **`Paused` forgotten.** Mitigated by the state being in the header on every
  route, by naming the consequence in the label, and by not persisting it.
