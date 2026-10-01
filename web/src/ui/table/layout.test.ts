import { describe, expect, it } from 'vitest';

import { solveColumns, type SolveResult, type SolverColumn } from './layout.ts';

/** A column that is 100 px, cannot be shortened and does not grow, unless `over` says otherwise. */
const col = (id: string, over: Partial<SolverColumn> = {}): SolverColumn => ({
  id,
  intrinsic: 100,
  min: 50,
  max: 400,
  grow: false,
  truncates: false,
  priority: 'high',
  ...over,
});

const total = (r: SolveResult) => Object.values(r.widths).reduce((a, b) => a + b, 0);

describe('everything fits', () => {
  it('gives each column its base and hides nothing', () => {
    const r = solveColumns({ W: 1000, cols: [col('a'), col('b', { intrinsic: 150 })] });
    expect(r).toEqual({ template: '100px 150px', widths: { a: 100, b: 150 }, hidden: [], overflow: false });
  });

  it('clamps the measured width into the column bounds', () => {
    const r = solveColumns({
      W: 2000,
      cols: [col('small', { intrinsic: 10 }), col('huge', { intrinsic: 900 })],
    });
    expect(r.widths).toEqual({ small: 50, huge: 400 });
  });

  it('fits exactly when the bases add up to the width', () => {
    const r = solveColumns({ W: 200, cols: [col('a'), col('b')] });
    expect(r.hidden).toEqual([]);
    expect(r.overflow).toBe(false);
  });

  it('solves an empty column set', () => {
    expect(solveColumns({ W: 500, cols: [] })).toEqual({ template: '', widths: {}, hidden: [], overflow: false });
  });
});

describe('growing', () => {
  it('shares spare width equally between grow columns and leaves the rest at base', () => {
    const r = solveColumns({
      W: 700,
      cols: [col('a', { grow: true }), col('b'), col('c', { grow: true })],
    });
    expect(r.template).toBe('minmax(100px, 1fr) 100px minmax(100px, 1fr)');
    expect(r.widths).toEqual({ a: 300, b: 100, c: 300 });
  });

  it('keeps a grow column that is already wider than its share at its base', () => {
    const r = solveColumns({
      W: 350,
      cols: [col('a', { grow: true }), col('b', { grow: true, intrinsic: 200 })],
    });
    expect(r.widths).toEqual({ a: 150, b: 200 });
  });

  it('fills the width exactly', () => {
    const r = solveColumns({ W: 1333, cols: [col('a', { grow: true }), col('b'), col('c', { grow: true })] });
    expect(total(r)).toBeCloseTo(1333, 6);
  });
});

describe('shrinking', () => {
  const cols = [
    col('a', { intrinsic: 300, min: 100, truncates: true }),
    col('b', { intrinsic: 200, min: 100, truncates: true }),
    col('c', { intrinsic: 100 }),
  ];

  it('shrinks truncatable columns in proportion to what they can give up', () => {
    // 600 needed, 450 available: 150 to find from a (200 to give) and b (100 to give).
    const r = solveColumns({ W: 450, cols });
    expect(r.widths).toEqual({ a: 200, b: 150, c: 100 });
    expect(r.hidden).toEqual([]);
    expect(r.overflow).toBe(false);
  });

  it('never shrinks a column that cannot be shortened', () => {
    expect(solveColumns({ W: 400, cols }).widths.c).toBe(100);
  });

  it('stops at the minimums and still fits', () => {
    const r = solveColumns({ W: 300, cols });
    expect(r.widths).toEqual({ a: 100, b: 100, c: 100 });
    expect(r.overflow).toBe(false);
  });

  it('never exceeds the width, whatever the rounding', () => {
    for (let W = 300; W < 600; W += 7) {
      expect(total(solveColumns({ W, cols }))).toBeLessThanOrEqual(W);
    }
  });

  it('lets a grow column take up the pixel the rounding left over', () => {
    const r = solveColumns({
      W: 451,
      cols: [col('a', { intrinsic: 300, min: 100, truncates: true, grow: true }), col('b', { intrinsic: 250 })],
    });
    expect(total(r)).toBeCloseTo(451, 6);
  });
});

describe('hiding', () => {
  const first = col('first', { priority: 'essential' });

  it('hides low before high, even when the low column is earlier', () => {
    const r = solveColumns({ W: 350, cols: [first, col('low', { priority: 'low' }), col('high'), col('high2')] });
    expect(r.hidden).toEqual(['low']);
  });

  it('hides from the inline end first within a priority', () => {
    const cols = [
      first,
      col('l1', { priority: 'low' }),
      col('l2', { priority: 'low' }),
      col('l3', { priority: 'low' }),
    ];
    expect(solveColumns({ W: 250, cols }).hidden).toEqual(['l2', 'l3']);
  });

  it('hides high columns, inline end first, once the low ones are gone', () => {
    const cols = [first, col('h1'), col('l1', { priority: 'low' }), col('h2')];
    expect(solveColumns({ W: 250, cols }).hidden).toEqual(['l1', 'h2']);
  });

  it('hides only as many as needed', () => {
    const cols = [first, col('l1', { priority: 'low' }), col('l2', { priority: 'low' })];
    const r = solveColumns({ W: 250, cols });
    expect(r.hidden).toEqual(['l2']);
    expect(Object.keys(r.widths)).toEqual(['first', 'l1']);
    expect(r.template).toBe('100px 100px');
  });

  it('shrinks before it hides', () => {
    const cols = [first, col('t', { intrinsic: 300, min: 100, truncates: true, priority: 'low' })];
    expect(solveColumns({ W: 250, cols }).hidden).toEqual([]);
  });

  it('lists the hidden columns in column order', () => {
    const cols = [first, col('a', { priority: 'low' }), col('b', { priority: 'low' }), col('c', { priority: 'low' })];
    expect(solveColumns({ W: 100, cols }).hidden).toEqual(['a', 'b', 'c']);
  });

  it('never hides an essential column', () => {
    const cols = [col('x'), col('node', { priority: 'essential' }), col('y', { priority: 'low' })];
    const r = solveColumns({ W: 150, cols });
    expect(r.hidden).toEqual(['y']);
    expect(r.overflow).toBe(true);
    expect(r.widths).toEqual({ x: 100, node: 100 });
  });

  it('never hides a column marked essential, whatever its priority', () => {
    const cols = [col('x'), col('node', { priority: 'low', essential: true })];
    expect(solveColumns({ W: 150, cols }).hidden).toEqual([]);
  });

  it('never hides the first data column, even at low priority', () => {
    const cols = [col('x', { priority: 'low' }), col('y', { priority: 'low' })];
    const r = solveColumns({ W: 150, cols });
    expect(r.hidden).toEqual(['y']);
    expect(r.widths).toEqual({ x: 100 });
  });

  it('never hides a column the operator chose to show; another one goes instead', () => {
    const cols = [first, col('shown', { priority: 'low', userShown: true }), col('other', { priority: 'high' })];
    expect(solveColumns({ W: 250, cols }).hidden).toEqual(['other']);
  });

  it('overflows when a column the operator showed leaves nothing else to hide', () => {
    const cols = [first, col('shown', { priority: 'low', userShown: true })];
    const r = solveColumns({ W: 150, cols });
    expect(r.hidden).toEqual([]);
    expect(r.overflow).toBe(true);
  });
});

describe('hysteresis', () => {
  const cols = [col('a', { intrinsic: 200, priority: 'essential' }), col('b', { priority: 'low' })];

  it('keeps a hidden column hidden until it fits with 16 px to spare', () => {
    const prev = solveColumns({ W: 299, cols });
    expect(prev.hidden).toEqual(['b']);
    expect(solveColumns({ W: 300, cols, prev }).hidden).toEqual(['b']);
    expect(solveColumns({ W: 315, cols, prev }).hidden).toEqual(['b']);
    expect(solveColumns({ W: 316, cols, prev }).hidden).toEqual([]);
  });

  it('does not hide a shown column until it no longer fits at all', () => {
    const prev = solveColumns({ W: 400, cols });
    expect(solveColumns({ W: 300, cols, prev }).hidden).toEqual([]);
    expect(solveColumns({ W: 299, cols, prev }).hidden).toEqual(['b']);
  });

  it('does not flap while the window is dragged back and forth across the boundary', () => {
    let prev: SolveResult | undefined;
    const states: boolean[] = [];
    const widths = [330, 320, 310, 300, 299, 295, 300, 305, 310, 315, 316, 320, 310, 300, 299];
    for (const W of widths) {
      prev = solveColumns({ W, cols, prev });
      states.push(prev.hidden.includes('b'));
    }
    const changes = states.filter((s, i) => i > 0 && s !== states[i - 1]).length;
    expect(changes).toBe(3);
  });

  it('applies the margin to the column that would come back, not to the whole table', () => {
    const three = [col('a', { priority: 'essential' }), col('b', { priority: 'low' }), col('c', { priority: 'low' })];
    const hiddenBefore = solveColumns({ W: 250, cols: three });
    expect(hiddenBefore.hidden).toEqual(['c']);
    expect(solveColumns({ W: 315, cols: three, prev: hiddenBefore }).hidden).toEqual(['c']);
    expect(solveColumns({ W: 316, cols: three, prev: hiddenBefore }).hidden).toEqual([]);
  });

  it('shows a column the operator forced on, whatever it hid before', () => {
    const prev = solveColumns({ W: 250, cols });
    const forced = [cols[0], { ...cols[1], userShown: true }];
    const r = solveColumns({ W: 250, cols: forced, prev });
    expect(r.hidden).toEqual([]);
    expect(r.overflow).toBe(true);
  });
});

describe('overflow', () => {
  it('falls back to the minimums and says so', () => {
    const cols = [
      col('a', { intrinsic: 300, min: 120, truncates: true, priority: 'essential' }),
      col('b', { intrinsic: 300, min: 120, truncates: true, priority: 'essential' }),
    ];
    const r = solveColumns({ W: 200, cols });
    expect(r.widths).toEqual({ a: 120, b: 120 });
    expect(r.template).toBe('120px 120px');
    expect(r.overflow).toBe(true);
    expect(r.hidden).toEqual([]);
  });
});

describe('widths the operator set', () => {
  it('are hard: not clamped to the bounds', () => {
    const r = solveColumns({ W: 2000, cols: [col('a', { userWidth: 700 }), col('b', { userWidth: 20 })] });
    expect(r.widths).toEqual({ a: 700, b: 20 });
    expect(r.template).toBe('700px 20px');
  });

  it('are never shrunk, however tight the table is', () => {
    const cols = [
      col('a', { userWidth: 300, truncates: true, min: 50 }),
      col('b', { intrinsic: 300, truncates: true, min: 100 }),
    ];
    const r = solveColumns({ W: 450, cols });
    expect(r.widths).toEqual({ a: 300, b: 150 });
  });

  it('are never grown into, even for a grow column', () => {
    const r = solveColumns({ W: 900, cols: [col('a', { userWidth: 200, grow: true }), col('b', { grow: true })] });
    expect(r.widths).toEqual({ a: 200, b: 700 });
    expect(r.template).toBe('200px minmax(100px, 1fr)');
  });

  it('widen by exactly 16 px per keyboard step', () => {
    const cols = (userWidth: number) => [
      col('a', { userWidth }),
      col('b', { intrinsic: 300, truncates: true, min: 100, grow: true }),
    ];
    const start = solveColumns({ W: 600, cols: cols(180) }).widths.a;
    const once = solveColumns({ W: 600, cols: cols(start + 16) }).widths.a;
    const twice = solveColumns({ W: 600, cols: cols(once + 16) }).widths.a;
    expect(twice - start).toBe(32);
    expect(twice).toBe(212);
  });

  it('can be hidden only when the operator has not shown them', () => {
    const cols = [col('a', { priority: 'essential' }), col('b', { userWidth: 300, priority: 'low' })];
    expect(solveColumns({ W: 200, cols }).hidden).toEqual(['b']);
  });
});

describe('W = 0', () => {
  it('shows every column at its base and hides nothing', () => {
    const cols = [col('a', { priority: 'essential' }), col('b', { intrinsic: 700, max: 400, priority: 'low' })];
    const r = solveColumns({ W: 0, cols, fixed: 84 });
    expect(r).toEqual({ template: '100px 400px', widths: { a: 100, b: 400 }, hidden: [], overflow: false });
  });

  it('keeps grow columns at their base', () => {
    const r = solveColumns({ W: 0, cols: [col('a', { grow: true }), col('b', { grow: true, intrinsic: 250 })] });
    expect(r.widths).toEqual({ a: 100, b: 250 });
    expect(r.template).toBe('minmax(100px, 1fr) minmax(250px, 1fr)');
  });
});

describe('the select and actions columns', () => {
  const cols = [col('a', { priority: 'essential' }), col('b', { priority: 'low' })];
  const fixed = 40 + 44;

  it('count against the width', () => {
    expect(solveColumns({ W: 200 + fixed, cols, fixed }).hidden).toEqual([]);
    expect(solveColumns({ W: 199 + fixed, cols, fixed }).hidden).toEqual(['b']);
  });

  it('count when shrinking', () => {
    const t = [col('a', { intrinsic: 300, min: 100, truncates: true })];
    expect(solveColumns({ W: 250 + fixed, cols: t, fixed }).widths.a).toBe(250);
  });

  it('are left out of the template and the widths', () => {
    const r = solveColumns({ W: 1000, cols, fixed });
    expect(Object.keys(r.widths)).toEqual(['a', 'b']);
    expect(r.template).toBe('100px 100px');
  });
});

describe('the announced width', () => {
  it('is the width the solver returns for the track, user-set, fixed and growing alike', () => {
    const r = solveColumns({
      W: 900,
      fixed: 84,
      cols: [col('a', { userWidth: 232 }), col('b', { grow: true }), col('c', { grow: true, intrinsic: 150 })],
    });
    expect(r.widths.a).toBe(232);
    expect(total(r) + 84).toBeCloseTo(900, 6);
    expect(Object.keys(r.widths)).toEqual(['a', 'b', 'c']);
  });
});
