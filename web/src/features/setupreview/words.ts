/** Category and severity in the words the screen uses. Colour is only ever redundant. */
export const CATEGORY_LABELS: Record<string, string> = {
  HIGH_AVAILABILITY: 'High availability',
  CLUSTERING: 'Clustering',
  DURABILITY: 'Durability',
  MESSAGE_SAFETY: 'Message safety',
  SECURITY: 'Security',
};

export const CATEGORY_ORDER = ['HIGH_AVAILABILITY', 'CLUSTERING', 'DURABILITY', 'MESSAGE_SAFETY', 'SECURITY'];

export const SEVERITY_WORDS: Record<string, string> = {
  CRITICAL: 'Critical',
  WARNING: 'Warning',
  INFO: 'Info',
};

export const SEVERITY_MEANING: Record<string, string> = {
  CRITICAL: 'data can be lost, duplicated or diverge under a foreseeable event',
  WARNING: 'messages can be stranded or service degraded',
  INFO: 'a hardening step',
};

export function plural(n: number, one: string, many = `${one}s`): string {
  return `${n} ${n === 1 ? one : many}`;
}

export const SEVERITY_FILTERS = ['CRITICAL', 'WARNING', 'INFO'] as const;
export type SeverityFilter = (typeof SEVERITY_FILTERS)[number];
