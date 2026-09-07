import dayjs from 'dayjs';
import timezonePlugin from 'dayjs/plugin/timezone';
import utcPlugin from 'dayjs/plugin/utc';

import type { MetricSeries } from '../api/client.ts';
import { displayZone } from '../app/timezone.ts';
import { rangeSpec, type MetricRange } from './ranges.ts';

// Both plugins, and `utc` first: dayjs's `timezone` is built on top of it.
dayjs.extend(utcPlugin);
dayjs.extend(timezonePlugin);

/**
 * Format an instant in the operator's chosen zone.
 *
 * Bare `dayjs(ms).format(...)` renders in the browser's zone, which used to leave
 * the charts and the tables disagreeing on every deployment where the two differed
 * — the axis in local time, every table in UTC, and nothing saying so. Everything
 * that renders a metric timestamp goes through here.
 */
export function formatInZone(ms: number, pattern: string): string {
  try {
    return dayjs(ms).tz(displayZone()).format(pattern);
  } catch {
    return dayjs(ms).utc().format(pattern);
  }
}

/**
 * The one place the metric charts agree about time (ADR-0055).
 *
 * Every chart plots epoch milliseconds on a numeric axis with a time scale, so a
 * sample's horizontal position is a function of *when* it was taken rather than
 * of where it sits in the returned array. That is what makes a stretch with no
 * samples render as proportional empty space: on the category axis this replaced,
 * a bucket the server omitted did not exist, and the two points either side of a
 * scrape outage were drawn adjacent — the `metrics` capability requires a visible
 * gap there, and the axis was why it never appeared.
 *
 * Tick format and the tooltip's label come from here too, so the synchronised
 * crosshair reads identically in every panel.
 */

/** Tick label format per range. Fifteen minutes and seven days are not the same axis. */
const TICK_FORMAT: Record<MetricRange, string> = {
  '15m': 'HH:mm:ss',
  '1h': 'HH:mm',
  '6h': 'HH:mm',
  '24h': 'ddd HH:mm',
  '7d': 'MMM D',
};

/** The tooltip is read deliberately, so it always carries the date as well. */
const TOOLTIP_FORMAT: Record<MetricRange, string> = {
  '15m': 'MMM D HH:mm:ss',
  '1h': 'MMM D HH:mm',
  '6h': 'MMM D HH:mm',
  '24h': 'MMM D HH:mm',
  '7d': 'MMM D HH:mm',
};

export function tickFormatter(range: MetricRange): (ms: number) => string {
  const format = TICK_FORMAT[range] ?? TICK_FORMAT['1h'];
  return (ms) => formatInZone(ms, format);
}

export function labelFormatter(range: MetricRange): (ms: number) => string {
  const format = TOOLTIP_FORMAT[range] ?? TOOLTIP_FORMAT['1h'];
  return (ms) => formatInZone(ms, format);
}

/**
 * Props for the recharts `XAxis` behind every metric chart.
 *
 * `type: 'number'` with `scale: 'time'` is the whole decision. The explicit
 * `[from, to]` domain — rather than `['dataMin', 'dataMax']` — keeps the plotted
 * width equal to the *requested* window, so a series that stops halfway through
 * the range renders as a chart that is half empty rather than one that silently
 * rescales to whatever data survived.
 */
export function timeAxisProps(range: MetricRange, from: number, to: number) {
  return {
    type: 'number' as const,
    scale: 'time' as const,
    domain: [from, to] as [number, number],
    tickFormatter: tickFormatter(range),
    minTickGap: 48,
    stroke: 'var(--as-chart-axis)',
  };
}

/**
 * `integral` is for a metric that only ever takes whole values — a consumer
 * count. Without it recharts picks fractional ticks for a small domain, and the
 * integer formatter renders every one of them as the same "0": an axis of five
 * identical labels beside bars of visibly different heights.
 */
export function yAxisProps(options: { integral?: boolean } = {}) {
  return {
    stroke: 'var(--as-chart-axis)',
    width: 56,
    ...(options.integral ? { allowDecimals: false } : {}),
  };
}

export function gridProps() {
  return { stroke: 'var(--as-chart-grid)' };
}

/** Bucket boundaries a series is expected to cover, used to size the empty case. */
export function bucketCount(range: MetricRange): number {
  const spec = rangeSpec(range);
  return Math.round(spec.windowMs / spec.stepMs);
}

/**
 * Merge any number of series onto one row per timestamp, keyed by epoch ms.
 *
 * Series are merged by timestamp rather than by index because a metric with no
 * samples in a bucket is simply omitted from its series, so two series over the
 * same window can carry different bucket sets. Zipping them by position would
 * silently pair a value with the wrong instant.
 */
export function mergeByTimestamp(
  named: Array<{ name: string; series: MetricSeries | undefined; field?: 'value' | 'peak' }>,
): Array<Record<string, number>> {
  const rows = new Map<number, Record<string, number>>();
  for (const { name, series, field = 'value' } of named) {
    for (const point of series?.points ?? []) {
      const ts = Date.parse(point.ts);
      if (Number.isNaN(ts)) continue;
      const value = field === 'peak' ? point.peak : point.value;
      if (value === null || value === undefined) continue;
      const row = rows.get(ts) ?? { ts };
      row[name] = value;
      rows.set(ts, row);
    }
  }
  return [...rows.keys()].sort((a, b) => a - b).map((ts) => rows.get(ts)!);
}

/** The last point of a series, or `null` when there is none to state. */
export function latest(series: MetricSeries | undefined): number | null {
  const points = series?.points;
  if (!points || points.length === 0) return null;
  return points[points.length - 1].value;
}

/** The first point of a series, for stating movement across the window. */
export function earliest(series: MetricSeries | undefined): number | null {
  const points = series?.points;
  if (!points || points.length === 0) return null;
  return points[0].value;
}

const COMPACT = new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 });
const INTEGER = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 });
const RATE = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 });

/** Depth runs to millions on a real cluster; an axis tick cannot carry the digits. */
export function formatCount(value: number): string {
  return COMPACT.format(value);
}

export function formatExact(value: number): string {
  return INTEGER.format(value);
}

export function formatRate(value: number): string {
  return RATE.format(value);
}
