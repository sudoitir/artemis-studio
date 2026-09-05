/**
 * Preset relative metric windows. Kept out of `router.tsx` so importing it (a
 * component like `RangePicker` needs the runtime array, not just the type)
 * never has to evaluate the whole route tree — that module calls
 * `createRootRoute`/`createRoute` at import time, which only a real router
 * context (or a full mock of it) can satisfy.
 */
export const METRIC_RANGES = ['15m', '1h', '6h', '24h', '7d'] as const;
export type MetricRange = (typeof METRIC_RANGES)[number];
