import type { FlowNodeView } from './api.ts';

/**
 * What the flow view is showing, as URL search params (non-negotiable #9): a shared or reloaded
 * address restores the same focus, ranking, grouping, bound and sort.
 */

export const FLOW_RANKS = ['IN', 'OUT', 'BACKLOG'] as const;
export const FLOW_GROUPINGS = ['CLIENT_ID', 'USER', 'HOST'] as const;
export const FLOW_LIMITS = [20, 40, 100, 200] as const;
export const DEFAULT_LIMIT = 40;

export type FlowRank = (typeof FLOW_RANKS)[number];
export type FlowGroupBy = (typeof FLOW_GROUPINGS)[number];

export interface FlowSearch {
  /** The graph is the default view; the table is its accessible twin. */
  tab?: 'table';
  /** `client:<name>`, `address:<name>` or `queue:<name>`. */
  focus?: string;
  hops?: number;
  rank?: FlowRank;
  limit?: number;
  groupBy?: FlowGroupBy;
  /** Table sort: a column id, `-` prefixed for descending. */
  sort?: string;
}

const FOCUS = /^(client|address|queue):.+$/;

export function validateFlowSearch(raw: Record<string, unknown>): FlowSearch {
  const out: FlowSearch = {};
  if (raw.tab === 'table') out.tab = 'table';
  if (typeof raw.focus === 'string' && FOCUS.test(raw.focus)) out.focus = raw.focus;
  const hops = Number(raw.hops);
  if (Number.isInteger(hops) && hops >= 2 && hops <= 3) out.hops = hops;
  if (typeof raw.rank === 'string' && (FLOW_RANKS as readonly string[]).includes(raw.rank) && raw.rank !== 'IN') {
    out.rank = raw.rank as FlowRank;
  }
  const limit = Number(raw.limit);
  if ((FLOW_LIMITS as readonly number[]).includes(limit) && limit !== DEFAULT_LIMIT) out.limit = limit;
  if (
    typeof raw.groupBy === 'string' &&
    (FLOW_GROUPINGS as readonly string[]).includes(raw.groupBy) &&
    raw.groupBy !== 'CLIENT_ID'
  ) {
    out.groupBy = raw.groupBy as FlowGroupBy;
  }
  if (typeof raw.sort === 'string' && /^-?[a-z]+$/.test(raw.sort)) out.sort = raw.sort;
  return out;
}

/** `queue:orders` → `{ kind: 'queue', name: 'orders' }`; anything else → null. */
export function parseFocus(focus: string | undefined): { kind: 'client' | 'address' | 'queue'; name: string } | null {
  if (!focus || !FOCUS.test(focus)) return null;
  const colon = focus.indexOf(':');
  return { kind: focus.slice(0, colon) as 'client' | 'address' | 'queue', name: focus.slice(colon + 1) };
}

/** The URL focus for a node: clients by their grouped label, resources by name. */
export function focusOf(node: FlowNodeView): string {
  const kind = node.kind === 'QUEUE' ? 'queue' : node.kind === 'ADDRESS' ? 'address' : 'client';
  return `${kind}:${node.label}`;
}
