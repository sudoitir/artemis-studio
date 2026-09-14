/**
 * How a rate becomes a line and moving dots (ADR-0080, flow-visualization spec: motion encodes
 * rate and is never the only carrier). Pure, so the mapping is tested without a canvas.
 */

/** Width tiers. Colour never carries the tier; the rate label always does. */
export type WidthTier = 'unknown' | 'idle' | 'light' | 'busy';

/** Messages per second at and above which an edge is drawn as busy. */
export const BUSY_RATE = 50;

/** The most dots the whole canvas animates at once, however large the graph. */
export const DOT_BUDGET = 400;

export function widthTier(rate: number | null | undefined): WidthTier {
  if (rate === null || rate === undefined) return 'unknown';
  if (rate <= 0) return 'idle';
  return rate >= BUSY_RATE ? 'busy' : 'light';
}

export const STROKE_WIDTH: Record<WidthTier, number> = { unknown: 1.25, idle: 1, light: 1.75, busy: 3 };

/**
 * One of five speed buckets on a log scale, or 0 for no motion. Buckets, not a continuous speed,
 * so a refresh whose rate stays inside its bucket leaves a running animation untouched.
 *
 * `(0, 1)` → 1, `[1, 10)` → 2, `[10, 100)` → 3, `[100, 1000)` → 4, `≥ 1000` → 5.
 */
export function speedBucket(rate: number | null | undefined): number {
  if (rate === null || rate === undefined || !(rate > 0)) return 0;
  return Math.min(5, Math.max(1, Math.floor(Math.log10(rate)) + 2));
}

/** Seconds for one dot to cross its edge. Faster with rate, capped so the busiest edge never strobes. */
export function crossingSeconds(bucket: number): number {
  return [0, 6, 4.5, 3.2, 2.4, 1.8][bucket] ?? 0;
}

/** Dots an edge would like, before the budget: denser as it gets busier. */
export function wantedDots(bucket: number): number {
  return [0, 1, 1, 2, 3, 4][bucket] ?? 0;
}

/**
 * Dots per edge under {@link DOT_BUDGET}. The busiest edges are served first; when the budget runs
 * out, the quietest edges lose their dots and keep their width and label.
 */
export function allocateDots(
  edges: ReadonlyArray<{ id: string; rate: number | null | undefined; animatable: boolean }>,
  budget: number = DOT_BUDGET,
): Map<string, number> {
  const out = new Map<string, number>();
  let left = budget;
  const ranked = [...edges].sort((a, b) => (b.rate ?? -1) - (a.rate ?? -1) || a.id.localeCompare(b.id));
  for (const edge of ranked) {
    const want = edge.animatable ? wantedDots(speedBucket(edge.rate)) : 0;
    const given = Math.min(want, left);
    out.set(edge.id, given);
    left -= given;
  }
  return out;
}
