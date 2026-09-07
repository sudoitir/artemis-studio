import { useSyncExternalStore } from 'react';

/**
 * Which timezone the UI *renders* instants in.
 *
 * Separate from `time.ts`, and the separation is the point. `time.ts` answers
 * "what time is it" — a fact about Studio's clock, which the browser is never
 * asked about. This answers "which offset from UTC should that fact be written
 * down in", which is a property of the person reading the screen and is exactly
 * the thing the browser *should* be asked about. Auto-detecting the zone is
 * therefore not a contradiction of the clock rule: a zone is not a time.
 *
 * The default is {@link AUTO}, which resolves to the browser's own zone on every
 * read rather than being captured once. An operator who travels, or whose machine
 * corrects its zone, follows along without touching a setting — which is what
 * "automatic" has to mean to be worth having. Picking a zone explicitly pins it.
 *
 * Persisted in `localStorage` rather than the URL: it describes the reader, not
 * what is being viewed, so it should not travel when a view is shared with a
 * colleague in another country (non-negotiable #9).
 */

const KEY = 'as:display:timezone';

/** Follow the browser, re-read every time rather than captured at load. */
export const AUTO = 'auto';

export const DEFAULT_ZONE = AUTO;

/** Whatever the browser currently believes its zone to be. */
export function localZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

function isUsable(zone: string): boolean {
  try {
    new Intl.DateTimeFormat('en-GB', { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

/**
 * A stored choice is honoured as stored.
 *
 * Deliberately no validation-then-discard here. An operator who picked a zone must
 * find that zone still selected next time, and a preference that quietly reverts to
 * the default is worse than one that renders oddly — they would have no way to tell
 * it had happened, and would keep re-picking it. A value the runtime cannot format
 * is handled where formatting happens (`absoluteLabel` falls back to UTC for that
 * one label) rather than by erasing the choice. Only genuinely absent storage
 * yields the default.
 */
function load(): string {
  try {
    const stored = window.localStorage.getItem(KEY);
    if (stored) return stored;
  } catch {
    // Storage can be unavailable outright (private mode, blocked site data).
  }
  return DEFAULT_ZONE;
}

let preference = load();
const listeners = new Set<() => void>();

/**
 * What the operator chose, which may be {@link AUTO}. This is what the picker
 * shows; {@link displayZone} is what formatters use.
 */
export function displayZonePreference(): string {
  return preference;
}

/** The zone to render instants in. Read synchronously, so formatters need no hook. */
export function displayZone(): string {
  if (preference === AUTO) return localZone();
  // A stored zone this runtime cannot format still renders — as UTC, for this
  // label only. The choice itself is left alone; see `load`.
  return isUsable(preference) ? preference : 'UTC';
}

export function setDisplayZone(next: string): void {
  if (next === preference) return;
  if (next !== AUTO && !isUsable(next)) return;
  preference = next;
  try {
    window.localStorage.setItem(KEY, next);
  } catch {
    // An unpersisted preference still applies for this session, which is better
    // than refusing to change it at all.
  }
  for (const l of listeners) l();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Subscribe a view to the display zone.
 *
 * Any component that renders an absolute timestamp must call this, even if it
 * ignores the returned value: `absoluteLabel` reads the zone from module state so
 * it can be used inside a table's column accessor, which means nothing re-renders
 * on a zone change unless something subscribed.
 */
export function useDisplayZone(): string {
  return useSyncExternalStore(subscribe, displayZonePreference, () => DEFAULT_ZONE);
}

/**
 * Every zone the runtime knows, UTC and the operator's own first.
 *
 * `Intl.supportedValuesOf` is the runtime's own IANA list, so it stays current
 * without this project shipping a copy of the tz database. Where it is missing,
 * the short list is still enough to be useful rather than empty.
 */
export function zoneOptions(): { group: string; items: { value: string; label: string }[] }[] {
  const all =
    typeof Intl.supportedValuesOf === 'function'
      ? (Intl.supportedValuesOf('timeZone') as string[])
      : ['UTC', 'Europe/London', 'Europe/Berlin', 'America/New_York', 'Asia/Tokyo'];

  const local = localZone();
  return [
    {
      group: 'Common',
      items: [
        // Auto names the zone it currently resolves to, so the operator can see
        // what "automatic" actually means for them without selecting it first.
        { value: AUTO, label: `Automatic \u2014 ${local}` },
        { value: 'UTC', label: 'UTC \u2014 matches container and broker logs' },
      ],
    },
    {
      group: 'All timezones',
      items: all.filter((z) => z !== 'UTC').map((z) => ({ value: z, label: z })),
    },
  ];
}

/**
 * `+03:30`, or `UTC` — the suffix that keeps a rendered timestamp unambiguous.
 *
 * Computed for a given instant, not for now: half the world changes offset twice
 * a year, and an event from last winter must be labelled with the offset that was
 * in force when it happened, not the one in force today.
 */
export function zoneSuffix(ms: number, inZone = displayZone()): string {
  if (inZone === 'UTC') return 'UTC';
  try {
    const parts = new Intl.DateTimeFormat('en-GB', {
      timeZone: inZone,
      timeZoneName: 'longOffset',
    }).formatToParts(new Date(ms));
    const name = parts.find((p) => p.type === 'timeZoneName')?.value ?? '';
    // Intl renders a whole-hour zone as bare "GMT"; the rest as "GMT+03:30".
    return name === 'GMT' ? '+00:00' : name.replace('GMT', '');
  } catch {
    return '';
  }
}
