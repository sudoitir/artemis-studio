import type { FlowEdgeView } from './api.ts';

const compact = new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 });
const precise = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 });

/** A message rate for display. Never renders an unknown rate as zero (flow-visualization spec). */
export function rateLabel(edge: Pick<FlowEdgeView, 'rate' | 'rateSource' | 'kind'>): string {
  if (edge.rateSource === 'NONE') return 'not counted by broker';
  if (edge.rate === null || edge.rate === undefined) return 'measuring…';
  return `${formatRate(edge.rate)} msg/s`;
}

export function formatRate(rate: number): string {
  if (rate > 0 && rate < 0.1) return '<0.1';
  return rate >= 10_000 ? compact.format(rate) : precise.format(rate);
}

export function formatCount(n: number): string {
  return n >= 100_000 ? compact.format(n) : precise.format(n);
}

/** A total rate that may be unknown. */
export function totalRateLabel(rate: number | null | undefined): string {
  return rate === null || rate === undefined ? 'measuring…' : `${formatRate(rate)} msg/s`;
}

/** Where a rate came from, in the operator's words. */
export function rateSourceLabel(edge: Pick<FlowEdgeView, 'rateSource' | 'averagedOverSeconds'>): string {
  switch (edge.rateSource) {
    case 'SAMPLER':
      return 'sampled clients';
    case 'QUEUE_METRIC':
      return edge.averagedOverSeconds
        ? `queue metrics, ~${Math.round(edge.averagedOverSeconds / 60)} min average`
        : 'queue metrics';
    default:
      return 'broker keeps no count';
  }
}

export const FAULT_LABELS: Record<string, string> = {
  NO_CONSUMER: 'no consumer',
  STALLED: 'stalled',
};

export const RANK_LABELS: Record<string, string> = {
  IN: 'messages in',
  OUT: 'messages out',
  BACKLOG: 'backlog',
};

export const GROUP_LABELS: Record<string, string> = {
  CLIENT_ID: 'Client ID',
  USER: 'User',
  HOST: 'Host',
};

/**
 * A sortable number for a rate: an unknown rate sorts after every known one in both directions, so
 * "measuring" never lands among the idle rows.
 */
export function rateSortValue(rate: number | null | undefined, descending: boolean): number {
  if (rate === null || rate === undefined) return descending ? -Infinity : Infinity;
  return rate;
}
