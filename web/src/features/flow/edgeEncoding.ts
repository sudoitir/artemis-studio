/**
 * How a rate becomes a line and moving dots (ADR-0095, superseding ADR-0080's encoding clause).
 * One normalised value drives width, dot speed and dot count, so the channels never disagree.
 * Pure, so the mapping is tested without a canvas.
 */

/** Messages per second at and above which an edge is drawn at full weight and speed. */
export const RATE_CAP = 1000;

/** Thinnest and thickest line, in px. Idle and unknown sit at the floor and differ by dash. */
export const MIN_WIDTH = 2;
export const MAX_WIDTH = 10;

/** The most dots the whole canvas animates at once, however large the graph. */
export const DOT_BUDGET = 400;

/**
 * Throughput on a square-root scale in `[0, 1]`: 0 for no or unknown rate, 1 at {@link RATE_CAP}.
 * Square root, not linear, so a 5 msg/s edge is still visibly heavier than a 1 msg/s one.
 */
export function rateScale(rate: number | null | undefined): number {
  if (rate === null || rate === undefined || !(rate > 0)) return 0;
  return Math.sqrt(Math.min(rate, RATE_CAP) / RATE_CAP);
}

export function widthPx(rate: number | null | undefined): number {
  return MIN_WIDTH + (MAX_WIDTH - MIN_WIDTH) * rateScale(rate);
}

/**
 * Seconds for one dot to cross its edge: 6 s at a trickle, 1.8 s at the cap, so the busiest edge
 * never strobes. Rounded to 0.1 s so a refresh that barely moves the rate keeps the memoised dots
 * and their running animation.
 */
export function crossingSeconds(rate: number | null | undefined): number {
  return Math.round((6 - 4.2 * rateScale(rate)) * 10) / 10;
}

/** Dots an edge would like, before the budget: one at a trickle, four at the cap. */
export function wantedDots(rate: number | null | undefined): number {
  return rate != null && rate > 0 ? 1 + Math.round(3 * rateScale(rate)) : 0;
}

/** What a line's dash pattern says. Weight carries the rate; the dash carries this. */
export type LineState = 'unknown' | 'idle' | 'stale' | 'flowing';

export function lineState(rate: number | null | undefined, stale: boolean | undefined): LineState {
  if (stale) return 'stale';
  if (rate === null || rate === undefined) return 'unknown';
  return rate > 0 ? 'flowing' : 'idle';
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
    const want = edge.animatable ? wantedDots(edge.rate) : 0;
    const given = Math.min(want, left);
    out.set(edge.id, given);
    left -= given;
  }
  return out;
}
