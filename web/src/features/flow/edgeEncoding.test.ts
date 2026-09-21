import { describe, expect, it } from 'vitest';

import {
  allocateDots,
  crossingSeconds,
  lineState,
  MAX_WIDTH,
  MIN_WIDTH,
  rateScale,
  wantedDots,
  widthPx,
} from './edgeEncoding.ts';

describe('edge encoding', () => {
  it('puts idle and unknown at the width floor, told apart by dash only', () => {
    expect(widthPx(undefined)).toBe(MIN_WIDTH);
    expect(widthPx(null)).toBe(MIN_WIDTH);
    expect(widthPx(0)).toBe(MIN_WIDTH);
    expect(lineState(undefined, false)).toBe('unknown');
    expect(lineState(0, false)).toBe('idle');
    expect(lineState(3, false)).toBe('flowing');
    expect(lineState(3, true)).toBe('stale');
    expect(wantedDots(0)).toBe(0);
    expect(wantedDots(undefined)).toBe(0);
  });

  it('hits the endpoints: 2px and 6 s at a trickle, 10px and 1.8 s at the cap', () => {
    expect(widthPx(1e-9)).toBeCloseTo(2, 3);
    expect(crossingSeconds(1e-9)).toBe(6);
    expect(widthPx(1000)).toBe(MAX_WIDTH);
    expect(crossingSeconds(1000)).toBe(1.8);
    expect(wantedDots(1e-9)).toBe(1);
    expect(wantedDots(1000)).toBe(4);
  });

  it('clamps at 1000 msg/s', () => {
    for (const rate of [1000, 5000, 1e9]) {
      expect(widthPx(rate)).toBe(MAX_WIDTH);
      expect(crossingSeconds(rate)).toBe(1.8);
      expect(wantedDots(rate)).toBe(4);
    }
  });

  it('grows heavier, faster and denser with rate, never the other way', () => {
    const rates = [0.1, 0.5, 1, 5, 10, 50, 100, 500, 999, 1000];
    for (let i = 1; i < rates.length; i++) {
      expect(widthPx(rates[i])).toBeGreaterThan(widthPx(rates[i - 1]));
      expect(crossingSeconds(rates[i])).toBeLessThanOrEqual(crossingSeconds(rates[i - 1]));
      expect(wantedDots(rates[i])).toBeGreaterThanOrEqual(wantedDots(rates[i - 1]));
    }
  });

  it('drives width and speed from the same square-root value', () => {
    for (const rate of [0.3, 7, 42, 250, 800]) {
      const s = Math.sqrt(rate / 1000);
      expect(rateScale(rate)).toBeCloseTo(s, 12);
      expect(widthPx(rate)).toBeCloseTo(2 + 8 * s, 12);
      expect(crossingSeconds(rate)).toBeCloseTo(6 - 4.2 * s, 1);
      expect(wantedDots(rate)).toBe(1 + Math.round(3 * s));
    }
  });

  it('serves the busiest edges first and stops at the budget', () => {
    const edges = [
      { id: 'quiet', rate: 0.5, animatable: true },
      { id: 'busy', rate: 5_000, animatable: true },
      { id: 'mid', rate: 50, animatable: true },
      { id: 'divert', rate: 9_999, animatable: false },
      { id: 'measuring', rate: undefined, animatable: true },
    ];

    const dots = allocateDots(edges, 5);

    expect(dots.get('busy')).toBe(4);
    expect(dots.get('mid')).toBe(1); // wanted 2, one left in the budget
    expect(dots.get('quiet')).toBe(0);
    expect(dots.get('divert')).toBe(0);
    expect(dots.get('measuring')).toBe(0);
    expect([...dots.values()].reduce((a, b) => a + b, 0)).toBeLessThanOrEqual(5);
  });
});
