/** Status word + tone — colour is never the sole signal (non-negotiable #6), same pattern as AuditView's outcome(). */
export function severityTone(severity: string): { word: string; color: string } {
  if (severity === 'CRITICAL') return { word: 'critical', color: 'red' };
  if (severity === 'WARNING') return { word: 'warning', color: 'yellow' };
  return { word: 'info', color: 'gray' };
}

export const GAUGE_METRICS = ['messageCount', 'consumerCount', 'deliveringCount', 'scheduledCount'] as const;
export const RATE_METRICS = ['messagesAdded', 'messagesAcked'] as const;

export function metricKind(metric: string): 'gauge' | 'rate' {
  return (RATE_METRICS as readonly string[]).includes(metric) ? 'rate' : 'gauge';
}

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
