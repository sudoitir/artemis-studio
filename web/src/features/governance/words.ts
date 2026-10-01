import type { FindingView, RuleView } from './api.ts';

export const TARGETS = [
  { value: 'PROPERTY', label: 'Property' },
  { value: 'HEADER', label: 'Header' },
  { value: 'BODY_PATH', label: 'JSON body path' },
];

const ACTION_WORDS: Record<string, string> = {
  DROP: 'Drop',
  PARTIAL: 'Partial',
  REDACT: 'Redact',
  CLEAR: 'Leave clear',
};

export const FINDING_STATUSES = [
  { value: 'OPEN', label: 'Open' },
  { value: 'CONFIRMED', label: 'Confirmed' },
  { value: 'DISMISSED', label: 'Dismissed' },
  { value: 'ALL', label: 'All' },
];

export function targetLabel(target: string): string {
  return TARGETS.find((t) => t.value === target)?.label ?? target;
}

/** What a rule does to a value: its own action, or the class default, said as such. */
export function actionWords(r: RuleView): string {
  return r.action ? ACTION_WORDS[r.action] : `${ACTION_WORDS[r.defaultAction] ?? r.defaultAction} (default)`;
}

const LOCATION_WORD: Record<string, string> = { BODY: 'body', HEADER: 'header' };

/** Where in a message a finding was seen: the part, and the path within it. */
export function fieldOf(f: FindingView): string {
  const where = LOCATION_WORD[f.location] ?? 'property';
  return f.fieldPath ? `${where} ${f.fieldPath}` : where;
}

export function statusLabel(status: string): string {
  return FINDING_STATUSES.find((s) => s.value === status)?.label ?? status;
}
