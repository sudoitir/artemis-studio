import { describe, expect, it } from 'vitest';

import { earliest, formatCount, latest, mergeByTimestamp, tickFormatter, timeAxisProps } from './axis.ts';
import { rangeSpec } from './ranges.ts';

function series(points: Array<{ ts: string; value: number; peak?: number }>) {
  return { metric: 'm', kind: 'GAUGE', unit: 'count', points } as never;
}

describe('the metric time axis', () => {
  it('keys points by their instant, so an omitted bucket leaves a hole', () => {
    // 09:00, 09:01, then a five-minute hole, then 09:06.
    const rows = mergeByTimestamp([
      {
        name: 'added',
        series: series([
          { ts: '2026-09-04T09:00:00.000Z', value: 1 },
          { ts: '2026-09-04T09:01:00.000Z', value: 2 },
          { ts: '2026-09-04T09:06:00.000Z', value: 3 },
        ]),
      },
    ]);

    expect(rows.map((r) => r.added)).toEqual([1, 2, 3]);
    // The x value is the instant itself. On the category axis this replaced, the
    // three points were three equal slots and the hole was invisible; here the
    // second interval is five times the first, which is what draws the gap.
    expect(rows[1].ts - rows[0].ts).toBe(60_000);
    expect(rows[2].ts - rows[1].ts).toBe(300_000);
  });

  it('merges two series by instant rather than by position', () => {
    // `acked` is missing the first bucket, so zipping by index would pair its
    // 09:01 value with `added`'s 09:00 one.
    const rows = mergeByTimestamp([
      {
        name: 'added',
        series: series([
          { ts: '2026-09-04T09:00:00.000Z', value: 1 },
          { ts: '2026-09-04T09:01:00.000Z', value: 2 },
        ]),
      },
      { name: 'acked', series: series([{ ts: '2026-09-04T09:01:00.000Z', value: 9 }]) },
    ]);

    expect(rows).toEqual([
      { ts: Date.parse('2026-09-04T09:00:00.000Z'), added: 1 },
      { ts: Date.parse('2026-09-04T09:01:00.000Z'), added: 2, acked: 9 },
    ]);
  });

  it('reads peak from the peak field, and drops a bucket that has none', () => {
    const rows = mergeByTimestamp([
      {
        name: 'peak',
        field: 'peak',
        series: series([
          { ts: '2026-09-04T09:00:00.000Z', value: 1, peak: 4 },
          { ts: '2026-09-04T09:01:00.000Z', value: 2 },
        ]),
      },
    ]);
    expect(rows).toEqual([{ ts: Date.parse('2026-09-04T09:00:00.000Z'), peak: 4 }]);
  });

  it('spans the requested window, not the surviving data', () => {
    const from = Date.parse('2026-09-04T09:00:00.000Z');
    const to = Date.parse('2026-09-04T10:00:00.000Z');
    const props = timeAxisProps('1h', from, to);
    expect(props.type).toBe('number');
    expect(props.scale).toBe('time');
    // A series that stops halfway must render as a half-empty chart rather than
    // one silently rescaled to whatever data arrived.
    expect(props.domain).toEqual([from, to]);
  });

  it('changes tick granularity with the range', () => {
    const at = Date.parse('2026-09-04T09:07:30.000Z');
    expect(tickFormatter('15m')(at)).toMatch(/:\d\d:\d\d$/);
    expect(tickFormatter('7d')(at)).not.toMatch(/:/);
  });

  it('states a window as its bucket, so the two cannot drift', () => {
    expect(rangeSpec('1h')).toEqual({ windowMs: 3_600_000, step: 'PT1M', stepMs: 60_000 });
    expect(rangeSpec('15m').windowMs / rangeSpec('15m').stepMs).toBe(60);
  });

  it('reports the ends of a series, and null when there is nothing to report', () => {
    const s = series([
      { ts: '2026-09-04T09:00:00.000Z', value: 3 },
      { ts: '2026-09-04T09:01:00.000Z', value: 7 },
    ]);
    expect(earliest(s)).toBe(3);
    expect(latest(s)).toBe(7);
    expect(latest(undefined)).toBeNull();
    expect(latest(series([]))).toBeNull();
  });

  it('compacts a depth an axis tick could not otherwise carry', () => {
    expect(formatCount(4200)).toBe('4.2K');
    expect(formatCount(1_500_000)).toBe('1.5M');
  });
});
