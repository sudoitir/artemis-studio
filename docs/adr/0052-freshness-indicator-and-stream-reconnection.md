# ADR-0052: One global freshness indicator, and a stream that reconnects forever

- **Status**: accepted
- **Date**: 2026-09-06
- **Deciders**: maintainer

## Context

Studio is a console people watch during an incident, and nothing in it says
whether what is on the screen is current.

Fifteen query hooks refetch on an interval; seventeen do not refetch at all.
There is no refresh control, no "last updated", and no connection indicator on
any route. A queue depth that stopped updating five minutes ago renders exactly
like one that updated a second ago.

The live stream makes this worse rather than better, in two ways that were both
deliberate choices at the time and are both wrong now:

- `useClusterStream` stops reconnecting after two consecutive failures
  (ADR-0018's "fall back to polling"). On the seventeen screens that do not poll,
  falling back to polling means falling back to nothing.
- The keep-alive is an SSE **comment**. A comment keeps the socket warm and fires
  no `EventSource` event, so an intermediary that drops the connection without a
  clean close leaves the client sitting on a dead socket believing it is live.
  There is no timeout to catch it, because there is no frame to miss.

The product's own standard is the argument here: capability gaps are stated
rather than silently hidden (non-negotiable 5). Stale data is the same class of
problem — a screen that has stopped updating and does not say so is worse than
one that admits it, because the operator acts on it.

## Decision

We will show **one global freshness indicator** in the application header, on
every route, and make the **stream reconnect indefinitely**.

**The indicator is derived, not wired.** It reads the query cache for the queries
that currently have active observers — which is exactly "what this screen
depends on" — and reports the newest `dataUpdatedAt` among them, whether any is
fetching, and whether any is in error. No screen can forget to opt in, and a
screen added later is covered the day it is written.

**Newest, not oldest.** `lastUpdatedAt` is the most recent successful fetch on
the screen, because that is what the operator watches change. Honesty about the
rest comes from the states, not from a pessimistic number: any active query in
error puts the whole indicator into `Offline`.

**Stream health and data health are separate states.** `Live`, `Polling`,
`Reconnecting…`, `Offline`, `Paused`. A stream that is down while the queries
succeed is `Polling`, not `Offline` — conflating them would cry wolf on every
proxy hiccup.

**The stream reconnects with capped exponential backoff and full jitter** (1s
floor, 30s cap), forever. Full jitter because a restarted Studio would otherwise
be hit by every open tab at once — the broker-friendly-by-construction rule
applied to Studio itself.

**The client treats silence as failure.** Any frame resets a watchdog; expiry
closes the connection and reconnects. This requires observable frames, so
`SseHub.heartbeat` sends a named `ping` **event** rather than a comment. It is
additive: a client that does not subscribe to `ping` is unaffected.

**Refresh and pause are both present.** Refresh refetches the active queries and
is also a palette command; it takes no hotkey, because the browser owns the two
an operator would reach for. Pause is the honest complement — the direct answer
to "I do not know whether this is updating" — implemented as one module-level
signal read by a `poll(ms)` helper at the existing interval sites. While paused,
stream invalidations mark data stale without refetching, so the indicator can
report that new data is waiting. Pause is memory-only: a persisted pause outlives
the reason it was set.

**No green.** `Live` is a bright neutral mark. Colour enters at `Reconnecting…`
and `Offline`, consistent with "health is the absence of colour".

This modifies ADR-0018's client-side fallback behaviour. Its server-side design —
one multiplexed stream per cluster, signals rather than payloads, refetch on
signal — is unchanged, as is ADR-0027's `Last-Event-ID` replay.

## Consequences

- **Every route gains an honest answer** to whether it is live, including the
  seventeen that never refetched.
- **A transient outage no longer needs a browser reload.** A proxy recycle, a
  laptop wake, or a rolling restart recovers on its own.
- **A dead tab costs one request every 30 seconds.** That is the price of never
  giving up, and it is bounded by the cap and by browsers throttling background
  timers.
- **The header gets busier.** One control, in a fixed position, on every route —
  accepted deliberately over per-panel badges, which multiply one fact across the
  screen and invite comparison between approximations of it.
- **The keep-alive is now part of the client contract.** Changing its interval
  changes when the watchdog fires; the watchdog window must stay comfortably
  above it.
- **Pause is a foot-gun.** Mitigated by the state being visible in the header on
  every route, by the label naming the consequence, and by not persisting it.

## Alternatives considered

- **A freshness badge per panel.** Rejected: twenty renderings of one fact, all
  approximations, inviting the reader to compare them.
- **Report the oldest `dataUpdatedAt`.** Technically "everything is at least this
  fresh", but one slow background query makes a live screen read as stale — a
  misleading pessimism that trains operators to ignore the indicator.
- **Make every hook poll.** Multiplies load for the many screens where it is
  pointless, and still says nothing about whether the last poll succeeded.
- **Keep the two-failure surrender and add a reconnect button.** Puts the
  recovery of a transient network fault on the operator, during the incident when
  they have least attention to spare.
- **Detect the dead socket with a request timeout instead of a heartbeat.**
  `EventSource` exposes no such timeout; the heartbeat is the only observable
  signal available.
