/**
 * Preset relative metric windows. Kept out of `router.tsx` so importing it (a
 * component like `RangePicker` needs the runtime array, not just the type)
 * never has to evaluate the whole route tree — that module calls
 * `createRootRoute`/`createRoute` at import time, which only a real router
 * context (or a full mock of it) can satisfy.
 */
export const METRIC_RANGES = ['15m', '1h', '6h', '24h', '7d'] as const;
export type MetricRange = (typeof METRIC_RANGES)[number];

/**
 * Each range's width and the bucket it asks the server for, in one place.
 *
 * The two travel together on purpose: the window advances in whole buckets
 * (ADR-0055), the axis chooses its tick format from the range, and the poll
 * cadence is derived from both. Splitting them across three modules is how they
 * drift.
 *
 * `step` is a hint. The server clamps it to the fastest sampling tier and widens
 * it to keep a response under its point cap, and reports what it actually used;
 * nothing here assumes the hint was honoured.
 */
export interface RangeSpec {
  /** Window width. */
  windowMs: number;
  /** Requested bucket width, as the ISO-8601 duration the endpoint takes. */
  step: string;
  /** The same bucket width in ms — what the window quantizes to. */
  stepMs: number;
}

const SECOND = 1_000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;

export const RANGE_SPEC: Record<MetricRange, RangeSpec> = {
  '15m': { windowMs: 15 * MINUTE, step: 'PT15S', stepMs: 15 * SECOND },
  '1h': { windowMs: HOUR, step: 'PT1M', stepMs: MINUTE },
  '6h': { windowMs: 6 * HOUR, step: 'PT5M', stepMs: 5 * MINUTE },
  '24h': { windowMs: 24 * HOUR, step: 'PT15M', stepMs: 15 * MINUTE },
  '7d': { windowMs: 7 * 24 * HOUR, step: 'PT1H', stepMs: HOUR },
};

export function rangeSpec(range: MetricRange): RangeSpec {
  return RANGE_SPEC[range] ?? RANGE_SPEC['1h'];
}
