import { useEffect, useState } from 'react';

import { displayZone, zoneSuffix } from './timezone.ts';

/**
 * The one place the UI knows what time it is, and it is not the browser's time.
 *
 * Studio already refuses to trust a broker's clock: ADR-0053 measures every
 * broker against Studio's, normalises foreign timestamps at the boundary where
 * they enter, and alerts when a clock is wrong. The browser is one more foreign
 * clock, and until this module existed it was the only one taken on faith. An
 * operator's laptop running four minutes fast inflated every age on the screen by
 * four minutes, rendered a live API token as `expired`, and asked the metrics API
 * for a window it had no samples for — an empty chart with nothing saying why.
 *
 * The rule, once, for the whole frontend:
 *
 * - Elapsed time or an expiry check against a **server** timestamp uses
 *   {@link serverNow}.
 * - A **browser** timestamp entering such a comparison — TanStack Query's
 *   `dataUpdatedAt` is the only one — is normalised with {@link toServerMs} at the
 *   boundary, so both sides are on the same timeline. Normalising at the boundary
 *   rather than at each reader is the same discipline ADR-0053 applies to broker
 *   time, and for the same reason: a mixed-clock subtraction is invisible in review.
 * - Absolute display formats a server instant in the browser's timezone. The zone
 *   is legitimately the operator's; the instant is not.
 * - Monotonic work — watchdogs, backoff, dwell timers — uses `performance.now()`
 *   or a plain `setTimeout`, never a wall clock. See `api/stream.ts`.
 */

const PROBE_PATH = '/api/v1/time';

/**
 * Same estimator, and the same constants, as `ClockOffsetRegistry` on the server,
 * so the two halves of the system agree about method as well as about the answer.
 */
const EWMA_ALPHA = 0.25;
/** A reading this much slower than the best seen carries too much queueing to learn from. */
const RTT_REJECT_FACTOR = 2.0;
/** Without decay one lucky early sample would lock the filter shut forever. */
const RTT_DECAY = 1.05;

/** Re-probe cadence. Crystal drift is parts-per-million; this is generous. */
const RESYNC_MS = 5 * 60_000;
/** A wall-clock jump larger than this is a step, not drift (`MonotonicClockWatch`). */
const STEP_MS = 1_000;
/** How often to look for such a step. */
const STEP_CHECK_MS = 10_000;
/**
 * A `ping` disagreeing with us by more than this triggers a fresh probe. Well above
 * a plausible one-way network delay, because a ping cannot measure its own latency
 * and must never be mistaken for a measurement.
 */
const PING_DRIFT_MS = 2_000;

let offsetMs = 0;
let bestRttMs = Number.POSITIVE_INFINITY;
let samples = 0;
let inFlight: Promise<void> | null = null;

const listeners = new Set<() => void>();

function notify() {
  for (const l of listeners) l();
}

/**
 * Take one measurement, NTP-style.
 *
 * `offset = server - (t0 + rtt / 2)` assumes the two legs of the round trip are
 * symmetric, which is least wrong when the round trip is shortest — queueing in
 * either direction only ever makes it longer. So a reading is allowed to teach the
 * estimate only when its round trip is at or near the best seen, and what survives
 * is smoothed by an EWMA so one outlier cannot move the answer.
 */
function offer(serverMs: number, t0: number, rttMs: number): void {
  if (!Number.isFinite(serverMs) || !Number.isFinite(rttMs) || rttMs < 0) return;

  bestRttMs = Math.min(bestRttMs * RTT_DECAY, rttMs);
  if (rttMs > bestRttMs * RTT_REJECT_FACTOR) return;

  const reading = serverMs - (t0 + rttMs / 2);
  // Half the round trip is the error bar on a single reading. Correcting by less
  // than that is noise dressed as precision, so it is held at zero instead.
  const uncertaintyMs = rttMs / 2;

  const next = samples === 0 ? reading : offsetMs + EWMA_ALPHA * (reading - offsetMs);
  samples += 1;

  const applied = Math.abs(next) > uncertaintyMs ? next : 0;
  if (applied === offsetMs) return;
  offsetMs = applied;
  notify();
}

/** Throw the estimate away. Used when the browser's clock stepped under us. */
function reset(): void {
  offsetMs = 0;
  bestRttMs = Number.POSITIVE_INFINITY;
  samples = 0;
  notify();
}

/**
 * Ask the server what time it is and fold the answer in.
 *
 * Failures are silent on purpose: the probe is not a screen and has no operator
 * waiting on it. A 401 or a dropped network leaves the previous estimate standing,
 * which is strictly better than reverting to the browser's clock, and the real
 * queries on the screen are what report that the server is unreachable.
 *
 * Concurrent callers join the request in flight rather than starting another.
 */
export function syncServerTime(): Promise<void> {
  if (inFlight) return inFlight;
  const t0 = Date.now();
  const m0 = performance.now();
  inFlight = fetch(PROBE_PATH, { credentials: 'same-origin', cache: 'no-store' })
    .then((res) => (res.ok ? res.json() : null))
    .then((body: { nowMs?: number } | null) => {
      if (body && typeof body.nowMs === 'number') {
        offer(body.nowMs, t0, performance.now() - m0);
      }
    })
    .catch(() => {})
    .finally(() => {
      inFlight = null;
    });
  return inFlight;
}

/**
 * The SSE keep-alive as a drift detector — never as a measurement.
 *
 * `SseHub.heartbeat` puts the server's clock in every `ping`, so the client is
 * told the time every twenty seconds for free. It is one-way with unmeasurable
 * latency, so it cannot teach the estimator; what it can do is notice that the
 * estimate has gone wrong and ask for a real probe.
 */
export function offerPing(data: unknown): void {
  const serverMs = typeof data === 'number' ? data : Number(data);
  if (!Number.isFinite(serverMs)) return;
  if (Math.abs(serverMs - serverNow()) > PING_DRIFT_MS) void syncServerTime();
}

/** Studio's clock, as best the browser can tell. The only wall-clock read in the app. */
export function serverNow(): number {
  return Date.now() + offsetMs;
}

/**
 * Put a browser-stamped instant on Studio's timeline.
 *
 * The only such instants are TanStack Query's `dataUpdatedAt`, which it stamps
 * with its own `Date.now()` when a response lands. Comparing one against
 * {@link serverNow} without this would reintroduce exactly the error this module
 * removes — and silently, because the number looks right.
 */
export function toServerMs(browserMs: number): number {
  return browserMs + offsetMs;
}

/** How far the browser's clock is from Studio's, in ms. Positive means the browser is ahead. */
export function browserOffsetMs(): number {
  // Not a bare negation: `-0` is a real value that survives into `Object.is`
  // comparisons and into a rendered "-0ms".
  return offsetMs === 0 ? 0 : -offsetMs;
}

/**
 * Keep the estimate honest for as long as the tab lives.
 *
 * Three triggers, because clocks go wrong in three ways: they drift (the
 * interval), they are corrected or the machine wakes from sleep (the step check
 * and `visibilitychange`), and the tab is backgrounded long enough that neither
 * timer ran (`visibilitychange` again — browsers throttle background timers).
 *
 * The step check is the client-side `MonotonicClockWatch`: `performance.now()`
 * advances monotonically, so comparing its delta against the wall clock's
 * separates a jump from ordinary drift. A step means the offset is now wrong by
 * the size of the jump, and unlearning that slowly through the EWMA would leave
 * every label wrong in the meantime.
 *
 * Called once from the application root. Returns a teardown for tests.
 */
export function startServerTimeSync(): () => void {
  void syncServerTime();

  let wall = Date.now();
  let mono = performance.now();

  const step = window.setInterval(() => {
    const nextWall = Date.now();
    const nextMono = performance.now();
    const drift = nextWall - wall - (nextMono - mono);
    wall = nextWall;
    mono = nextMono;
    if (Math.abs(drift) > STEP_MS) {
      reset();
      void syncServerTime();
    }
  }, STEP_CHECK_MS);

  const resync = window.setInterval(() => void syncServerTime(), RESYNC_MS);

  const onVisible = () => {
    if (document.visibilityState === 'visible') void syncServerTime();
  };
  document.addEventListener('visibilitychange', onVisible);

  return () => {
    window.clearInterval(step);
    window.clearInterval(resync);
    document.removeEventListener('visibilitychange', onVisible);
  };
}

/**
 * A clock that ticks to re-render a relative label, on Studio's time.
 *
 * Also re-renders when the offset itself changes, which matters more than it
 * looks: `MetricsView` ticks at the metric bucket width, up to an hour, and would
 * otherwise hold a window computed before the first probe resolved for that long.
 */
export function useServerNow(intervalMs = 1_000): number {
  const [now, setNow] = useState(() => serverNow());
  useEffect(() => {
    const tick = () => setNow(serverNow());
    const id = setInterval(tick, intervalMs);
    listeners.add(tick);
    tick();
    return () => {
      clearInterval(id);
      listeners.delete(tick);
    };
  }, [intervalMs]);
  return now;
}

// ── formatting ─────────────────────────────────────────────────────────────

/**
 * `4s`, `3m`, `2h`, `1d` — short enough to sit in a header or a table cell.
 *
 * Floors rather than rounds. An age is a lower bound on how long ago something
 * happened, and `59s` reading as `1m` overstates it; the operator reading a
 * staleness label before a destructive action is the reason to prefer the
 * conservative direction.
 */
export function elapsedLabel(ms: number): string {
  const seconds = Math.max(0, Math.floor(ms / 1_000));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  return `${Math.floor(hours / 24)}d`;
}

/**
 * A server instant, written down in the operator's chosen timezone.
 *
 * Fixed-width `YYYY-MM-DD HH:mm:ss` so a column of these sorts and scans by eye,
 * and **always suffixed with the zone it is in** — `Z` for UTC, `+03:30`
 * otherwise. The suffix is not decoration: the whole reason an operator picks a
 * zone is to line a screen up against something else, and a timestamp that does
 * not say which offset it is in is the one way this feature could mislead them.
 *
 * The offset is computed for *that instant*, not for now, so an event from last
 * winter carries the offset that was in force when it happened.
 *
 * Accepts both wire shapes: the ISO-8601 strings every DTO carries, and the
 * epoch-millis a broker's own message timestamps arrive as. Those are `0` when the
 * broker set none, which is not a time and is rendered as such.
 *
 * Reads the zone from module state rather than a hook so it can be called from a
 * table's column accessor. The consequence is that a view rendering one of these
 * must subscribe with `useDisplayZone()` or it will not repaint when the zone
 * changes.
 */
export function absoluteLabel(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '\u2014';
  const ms = typeof value === 'number' ? value : Date.parse(value);
  if (!Number.isFinite(ms) || ms <= 0) return '\u2014';

  const zone = displayZone();
  if (zone === 'UTC') return utcLabel(ms);

  try {
    const parts = new Intl.DateTimeFormat('en-GB', {
      timeZone: zone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    }).formatToParts(new Date(ms));
    const at = (type: Intl.DateTimeFormatPartTypes) =>
      parts.find((p) => p.type === type)?.value ?? '';
    const date = `${at('year')}-${at('month')}-${at('day')}`;
    const time = `${at('hour')}:${at('minute')}:${at('second')}`;
    return `${date} ${time} ${zoneSuffix(ms, zone)}`.trimEnd();
  } catch {
    // A zone the runtime cannot format is still a timestamp worth showing.
    return utcLabel(ms);
  }
}

/**
 * Seconds precision, always. Sub-second digits are noise in every column that
 * renders one of these, and showing them only when they happen to be non-zero
 * made a table's rows different widths for no reason the reader could act on.
 */
function utcLabel(ms: number): string {
  return new Date(ms).toISOString().slice(0, 19).replace('T', ' ') + 'Z';
}
