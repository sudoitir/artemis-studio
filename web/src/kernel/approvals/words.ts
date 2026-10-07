import type { HeldEvent, HeldOperationSummary, HeldState } from './api.ts';

type Tone = 'neutral' | 'info' | 'warning' | 'danger';

/**
 * A request's state in words, with the tone that emphasises it. Colour enters only where something went wrong
 * or is not known; a request that waits or ended as asked reads near-monochrome.
 */
export const STATE: Readonly<Record<HeldState, { word: string; tone: Tone }>> = {
  HELD: { word: 'Waiting for approval', tone: 'info' },
  APPROVED: { word: 'Approved', tone: 'neutral' },
  EXECUTING: { word: 'Running', tone: 'neutral' },
  REJECTED: { word: 'Rejected', tone: 'neutral' },
  CANCELLED: { word: 'Cancelled', tone: 'neutral' },
  EXPIRED: { word: 'Expired', tone: 'neutral' },
  SUCCEEDED: { word: 'Succeeded', tone: 'neutral' },
  FAILED: { word: 'Failed', tone: 'danger' },
  REFUSED: { word: 'Refused when run', tone: 'danger' },
  OUTCOME_UNKNOWN: { word: 'Outcome unknown', tone: 'warning' },
};

/** What each timeline entry records. */
export const EVENT: Readonly<Record<string, string>> = {
  REQUESTED: 'Requested',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  VOTE_REFUSED: 'Decision refused',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
  EXECUTING: 'Started running',
  SUCCEEDED: 'Succeeded',
  FAILED: 'Failed',
  REFUSED: 'Refused when run',
  OUTCOME_UNKNOWN: 'Outcome unknown',
};

export const eventWord = (event: HeldEvent) => EVENT[event.kind] ?? event.kind;

/** How the requester was signed in when they asked. */
export const AUTH_KIND: Readonly<Record<HeldOperationSummary['authKind'], string>> = {
  SESSION: 'Signed-in session',
  TOKEN: 'API token',
  AGENT: 'Assistant, through an API token',
};

/** A trait as an approver reads it. */
export const TRAIT: Readonly<Record<string, string>> = {
  DESTRUCTIVE: 'Destroys or moves data',
  BULK: 'Acts on many resources',
  SETTINGS: 'Changes settings',
  ACCESS_CONTROL: 'Changes who may do what',
  GATE_INTEGRITY: 'Changes the approval gate',
};

/**
 * Time left before a request lapses, to the second in its last minute: `2d 3h`, `1h 05m`, `4m 09s`, `42s`.
 * Floors, so it never claims more time than there is.
 */
export function remainingLabel(ms: number): string {
  const seconds = Math.max(0, Math.floor(ms / 1_000));
  const pad = (n: number) => String(n).padStart(2, '0');
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ${pad(seconds % 60)}s`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ${pad(minutes % 60)}m`;
  return `${Math.floor(hours / 24)}d ${hours % 24}h`;
}

/**
 * What a screen reader is told as a request nears its expiry: once at each of these marks, never every
 * second. `undefined` between marks.
 */
const MARKS_MIN = [1, 5, 15, 60] as const;
export function expiryMark(ms: number): string | undefined {
  if (ms <= 0) return 'The request has expired.';
  const minutes = ms / 60_000;
  const mark = MARKS_MIN.find((m) => minutes <= m);
  if (mark === undefined) return undefined;
  return mark === 1
    ? 'Less than a minute before the request expires.'
    : `Less than ${mark} minutes before the request expires.`;
}

/** "1,204 messages": the effect as a figure with its unit. */
export function effectLabel(effect: { count: number; unit: string }): string {
  return `${effect.count.toLocaleString('en-US')} ${effect.unit}`;
}
