/** Status word + tone — colour is never the sole signal (non-negotiable #6), same pattern as AuditView's outcome(). */
export function severityTone(severity: string): { word: string; color: string } {
  if (severity === 'CRITICAL') return { word: 'critical', color: 'red' };
  if (severity === 'WARNING') return { word: 'warning', color: 'yellow' };
  return { word: 'info', color: 'gray' };
}

export const GAUGE_METRICS = ['messageCount', 'consumerCount', 'deliveringCount', 'scheduledCount'] as const;
export const RATE_METRICS = ['messagesAdded', 'messagesAcked'] as const;

/**
 * Derived metrics are computed from more than one reading (ADR-0044). Their
 * subject universe is narrower than a raw metric's, so they carry their own
 * explanation in the rule form rather than looking like another gauge.
 */
export const DERIVED_METRICS = ['ackRatePerConsumer'] as const;

export function metricKind(metric: string): 'gauge' | 'rate' | 'derived' {
  if ((DERIVED_METRICS as readonly string[]).includes(metric)) return 'derived';
  return (RATE_METRICS as readonly string[]).includes(metric) ? 'rate' : 'gauge';
}

const METRIC_LABELS: Record<string, string> = {
  ackRatePerConsumer: 'ackRatePerConsumer — slow consumers',
};

export function metricLabel(metric: string): string {
  return METRIC_LABELS[metric] ?? metric;
}

/**
 * What a derived metric actually watches, stated in the form. `ackRatePerConsumer`
 * only evaluates queues that have consumers attached AND a backlog AND are not
 * paused — the triple that stops it paging on every quiet queue at 3am — and it
 * cannot name the individual consumer, only the queue on a node.
 */
export const METRIC_NOTES: Record<string, string> = {
  ackRatePerConsumer:
    'Only queues with consumers attached, a non-zero backlog, and not paused are evaluated — an idle or paused queue is not a slow consumer. Studio resolves this to a queue on a node; naming the individual consumer needs the broker\'s own slow-consumer detection.',
};

/**
 * A prefilled starting point rather than a seeded rule: a slow-consumer threshold
 * is workload-specific, so any value shipped on by default would be wrong for
 * most deployments. The operator still chooses the number.
 */
export const SLOW_CONSUMER_TEMPLATE = {
  name: 'Slow consumers',
  metric: 'ackRatePerConsumer',
  comparator: 'LT',
  threshold: 1,
  forSeconds: 300,
  severity: 'WARNING',
} as const;

export const STATE_CONDITIONS = [
  'SPLIT_BRAIN',
  'NODE_DOWN',
  'REPLICATION_BEHIND',
  'CLUSTER_DEGRADED',
] as const;

export const COMPARATORS = ['GT', 'GTE', 'LT', 'LTE', 'EQ', 'NE'] as const;

const COMPARATOR_WORDS: Record<string, string> = {
  GT: '>',
  GTE: '≥',
  LT: '<',
  LTE: '≤',
  EQ: '=',
  NE: '≠',
};

export function comparatorSymbol(comparator: string): string {
  return COMPARATOR_WORDS[comparator] ?? comparator;
}

const STATE_LABELS: Record<string, string> = {
  SPLIT_BRAIN: 'Split-brain',
  NODE_DOWN: 'Node down',
  REPLICATION_BEHIND: 'Replication behind',
  CLUSTER_DEGRADED: 'Cluster degraded',
};

export function stateConditionLabel(condition: string): string {
  return STATE_LABELS[condition] ?? condition;
}
