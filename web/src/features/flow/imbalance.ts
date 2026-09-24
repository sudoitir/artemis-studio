import type { FlowNodeShare } from './api.ts';
import { formatCount } from './flowFormat.ts';

/** One statement about how a queue or address is spread over the broker nodes (design D11). */
export interface Statement {
  kind: 'concentration' | 'stranded' | 'skew' | 'balanced' | 'unknown';
  text: string;
}

/** One node holding this much of a backlog of at least `CONCENTRATION_MIN` is stated. */
const CONCENTRATION_SHARE = 0.75;
const CONCENTRATION_MIN = 100;
/** A node's share of messages in exceeding its share out by this many points, above `SKEW_MIN_RATE` msg/s. */
const SKEW_POINTS = 40;
const SKEW_MIN_RATE = 1;

const list = new Intl.ListFormat('en', { type: 'conjunction' });
const known = (n: number | null | undefined): n is number => n !== null && n !== undefined;
const sum = (values: number[]) => values.reduce((a, b) => a + b, 0);

/**
 * The imbalance of one resource across its nodes, in words: colour never carries it alone. Every
 * figure is over the nodes that answered; a node that did not is stated as unknown and left out of
 * the percentages, never counted as zero.
 */
export function imbalance(shares: readonly FlowNodeShare[]): Statement[] {
  const answered = shares.filter((s) => !s.stale);
  const out: Statement[] = shares
    .filter((s) => s.stale)
    .map((s) => ({ kind: 'unknown', text: `${s.node} did not answer, so its share is unknown and left out.` }));

  const backlogs = answered.filter((s) => known(s.messageCount));
  const backlog = sum(backlogs.map((s) => s.messageCount!));
  if (answered.length >= 2 && backlog >= CONCENTRATION_MIN) {
    const top = backlogs.reduce((a, b) => (b.messageCount! > a.messageCount! ? b : a));
    const share = top.messageCount! / backlog;
    if (share >= CONCENTRATION_SHARE) {
      out.push({ kind: 'concentration', text: `${Math.round(share * 100)}% of the backlog is on ${top.node}.` });
    }
  }

  const consuming = answered.filter((s) => (s.consumerCount ?? 0) > 0);
  if (consuming.length > 0) {
    for (const s of answered) {
      if ((s.messageCount ?? 0) > 0 && s.consumerCount === 0) {
        const n = s.messageCount!;
        out.push({
          kind: 'stranded',
          text: `${s.node} holds ${formatCount(n)} ${n === 1 ? 'message' : 'messages'} and has no consumer; the consumers are on ${list.format(consuming.map((c) => c.node))}.`,
        });
      }
    }
  }

  const rated = answered.filter((s) => known(s.inRate) && known(s.outRate));
  const totalIn = sum(rated.map((s) => s.inRate!));
  const totalOut = sum(rated.map((s) => s.outRate!));
  if (answered.length >= 2 && totalIn >= SKEW_MIN_RATE && totalOut > 0) {
    for (const s of rated) {
      const inShare = (s.inRate! / totalIn) * 100;
      const outShare = (s.outRate! / totalOut) * 100;
      if (inShare - outShare >= SKEW_POINTS) {
        out.push({
          kind: 'skew',
          text: `${s.node} receives ${Math.round(inShare)}% of messages in but delivers ${Math.round(outShare)}% of messages out.`,
        });
      }
    }
  }

  if (!out.some((s) => s.kind !== 'unknown')) {
    if (answered.length >= 2) out.push({ kind: 'balanced', text: `Balanced across ${answered.length} nodes.` });
    else if (answered.length === 1) out.push({ kind: 'balanced', text: `Served by one node, ${answered[0].node}.` });
  }
  return out;
}
