import { describe, expect, it } from 'vitest';

import { allocateDots, crossingSeconds, speedBucket, wantedDots, widthTier } from './edgeEncoding.ts';

describe('edge encoding', () => {
  it('separates unknown from idle, and idle from moving', () => {
    expect(widthTier(undefined)).toBe('unknown');
    expect(widthTier(0)).toBe('idle');
    expect(widthTier(3)).toBe('light');
    expect(widthTier(500)).toBe('busy');
    expect(speedBucket(undefined)).toBe(0);
    expect(speedBucket(0)).toBe(0);
  });

  it('buckets rates by decade, so a small change keeps the same animation', () => {
    expect(speedBucket(0.4)).toBe(1);
    expect(speedBucket(4)).toBe(2);
    expect(speedBucket(42)).toBe(3);
    expect(speedBucket(48)).toBe(3);
    expect(speedBucket(420)).toBe(4);
    expect(speedBucket(42_000)).toBe(5);
  });

  it('moves faster and denser with rate, and never faster than the cap', () => {
    for (let b = 2; b <= 5; b++) {
      expect(crossingSeconds(b)).toBeLessThan(crossingSeconds(b - 1));
      expect(wantedDots(b)).toBeGreaterThanOrEqual(wantedDots(b - 1));
    }
    expect(crossingSeconds(5)).toBeGreaterThanOrEqual(1.5);
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
