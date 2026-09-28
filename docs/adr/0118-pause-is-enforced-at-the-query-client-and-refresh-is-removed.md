# ADR-0118: Pause is enforced once, at the query client, and the refresh control is removed

- **Status**: accepted
- **Date**: 2026-09-28
- **Deciders**: Mahdi Amirabdollahi

## Context

[ADR-0052](0052-freshness-indicator-and-stream-reconnection.md) put two controls in the
header: refresh and pause. Pause was built as a module-level signal read by a `poll(ms)`
helper, which each query hook wrapped around its `refetchInterval`.

That made pause opt-in, and opt-in did not hold:

- Any hook with a literal interval kept polling while the header said `Paused`. That
  covered two of Studio's own hooks, and every runtime plugin's hooks, because a plugin
  is written against TanStack Query rather than a Studio helper.
- Hooks that did opt in kept polling for one more interval. TanStack Query re-reads a
  function-valued `refetchInterval` only after the next fetch settles, so the timer that
  was already armed fired once more.

On a screen whose data came from such a hook, pressing pause changed the label and
nothing else. An operator who pauses a screen to read it, and then watches it move,
learns that the control lies.

The refresh control earns less than its header space. The live stream and the per-hook
intervals already keep a screen current, and resuming from pause refetches everything
on the screen. Reloading remains the operator's reflex for a screen they distrust.

## Decision

**Pause is enforced by TanStack Query's `focusManager`, not by each hook.** Every
observer's interval timer checks `focusManager.isFocused()` before it fetches. Studio
installs one focus listener (`installPauseSeam`, called from `main.tsx`) that reports
the tab as focused only while it is visible **and** refreshing is not paused. Every
interval, including a literal one and including a plugin's (plugins share Studio's copy
of `@tanstack/react-query`), stops at its next tick. The `poll(ms)` helper is deleted,
and hooks declare plain intervals.

The other two parts of ADR-0052's pause stay as they are. `mountRefetch` keeps a paused
screen from refetching on navigation, and stream signals mark data stale without
refetching while paused.

**The refresh control is removed**, from the header and from the command palette.
Resuming from pause still refetches the active queries.

## Consequences

- Pause holds for every query on every screen, including hooks written after today and
  by third parties, with nothing to remember.
- Pause takes effect at the next tick, not one interval late.
- TanStack's retryer also waits for focus, so a request that fails while paused retries
  after the operator resumes, not during the pause. That fits what pausing means.
- `focusManager` now means "may refresh on its own" rather than strictly "the tab has
  focus". Anything added later that reads it, such as `refetchOnWindowFocus`, inherits
  that meaning. Studio keeps `refetchOnWindowFocus` off.
- There is no one-click refetch. An operator who wants current data on a screen that
  does not poll reloads the page, or pauses and resumes.

## Alternatives considered

- **Keep `poll()` and fix the literal intervals.** This fixes today's gaps. It leaves
  every future hook and every plugin one forgotten wrapper away from the same bug.
- **Export `poll()` from the SDK.** This asks every plugin author to opt in to a
  behaviour the operator expects everywhere. It is still opt-in.
- **Cancel in-flight queries on pause.** This throws away work the operator did not ask
  to discard and does nothing about the timers.
