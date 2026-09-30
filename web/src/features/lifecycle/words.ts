import type { ApiError } from '../../kernel/api/request.ts';
import type { StoreView } from './api.ts';

/** A byte figure an operator reads at a glance; an unknown one is said to be unknown. */
export function bytes(n: number | null | undefined): string {
  if (n == null) return 'unknown';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = n;
  let unit = 0;
  while (Math.abs(value) >= 1000 && unit < units.length - 1) {
    value /= 1000;
    unit += 1;
  }
  return `${value.toLocaleString(undefined, { maximumFractionDigits: unit === 0 ? 0 : 1 })} ${units[unit]}`;
}

export function count(n: number | null | undefined): string {
  return n == null ? 'unknown' : n.toLocaleString();
}

/** `7d`, `72h`, `forever` in words: "7 days", "72 hours", "Forever". */
export function retentionWords(value: string): string {
  if (value === 'forever') return 'Forever';
  const match = /^(\d+)([dhms])$/.exec(value);
  if (!match) return value;
  const n = Number(match[1]);
  const unit = { d: 'day', h: 'hour', m: 'minute', s: 'second' }[match[2] as 'd' | 'h' | 'm' | 's'];
  return `${n.toLocaleString()} ${unit}${n === 1 ? '' : 's'}`;
}

export function quotaUnit(store: StoreView): string {
  return store.quotaUnit === 'BYTES' ? 'MiB' : 'thousand rows';
}

export function quotaWords(store: StoreView): string {
  if (store.quota === 0) return 'None';
  const used = store.quotaUsedPercent == null ? '' : ` · ${store.quotaUsedPercent}% used`;
  return `${store.quota.toLocaleString()} ${quotaUnit(store)}${used}`;
}

/** What to do about a failed read: a refusal is about grants, anything else about Studio itself. */
export function nextStep(error: ApiError): string {
  return error.status === 403
    ? 'Viewing this needs the data:read permission; ask an administrator for it.'
    : "Reload to try again. If it keeps failing, Studio's log names the cause.";
}
