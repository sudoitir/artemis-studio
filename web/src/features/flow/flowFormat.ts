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
  BRIDGE_DOWN: 'not connected',
  PARTIAL_PRESENCE: 'partly deployed',
};

/** How the table and inspector name each kind of edge, from its source's side. */
export const RELATION: Record<string, string> = {
  PRODUCE: 'produces to',
  ROUTE: 'routes to',
  CONSUME: 'consumed by',
  DIVERT: 'diverts to',
  BRIDGE: 'bridges to',
  CLUSTER_HOP: 'redistributes to',
  WILDCARD: 'also reaches',
  DEAD_LETTER: 'dead-letters to',
  EXPIRY: 'expires to',
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

/** The text an edge carries: what it does, the rate where one is counted, and any fault, in words. */
export function edgeText(view: FlowEdgeView): string {
  const parts: string[] = [];
  switch (view.kind) {
    case 'ROUTE':
      if (view.delivery) parts.push(view.delivery === 'COPY' ? 'copy' : 'shared');
      if (view.filter) parts.push('filtered');
      if (view.bypassed) parts.push('bypassed by an exclusive divert');
      parts.push(rateLabel(view));
      break;
    case 'DIVERT':
      parts.push(view.exclusive ? 'reroutes' : 'copies');
      if (view.filter) parts.push('filtered');
      if (view.transformer) parts.push('transformed');
      parts.push(rateLabel(view));
      break;
    case 'BRIDGE':
      parts.push('bridge', rateLabel(view));
      break;
    case 'CLUSTER_HOP':
      parts.push('redistributes', rateLabel(view));
      break;
    case 'WILDCARD':
      parts.push('matches');
      break;
    case 'DEAD_LETTER':
      parts.push('on failure');
      break;
    case 'EXPIRY':
      parts.push('on expiry');
      break;
    default:
      parts.push(rateLabel(view));
  }
  for (const f of view.faults ?? []) {
    parts.push(
      f === 'PARTIAL_PRESENCE' && view.presentOn !== undefined && view.presentOn !== null
        ? `on ${view.presentOn} of ${view.presentOf} nodes`
        : (FAULT_LABELS[f] ?? f.toLowerCase()),
    );
  }
  if (view.studio) parts.push('Studio capture');
  if (view.stale) parts.push('stale');
  return parts.join(' · ');
}
