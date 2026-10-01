import { absoluteLabel } from '../../kernel/time/time.ts';
import type { AlertDeliveryView, AlertRuleView, NotificationChannelView } from './api.ts';
import { comparatorSymbol, stateConditionLabel } from './severity.ts';

export function ruleCondition(rule: AlertRuleView): string {
  if (rule.kind === 'METRIC_THRESHOLD') {
    return `${rule.metric} ${comparatorSymbol(rule.comparator ?? '')} ${rule.threshold}`;
  }
  return stateConditionLabel(rule.stateCondition ?? '');
}

/** When a delivery went out, or what became of it. */
export function deliveryWhen(d: AlertDeliveryView): string {
  if (d.state === 'SENT') return absoluteLabel(d.deliveredAt);
  if (d.state === 'PENDING') return `next ${absoluteLabel(d.nextAttemptAt)}`;
  return 'gave up';
}

/** Why a channel cannot deliver yet, when its secret is missing. */
export function missingSecretHint(c: NotificationChannelView): string {
  if (c.hasSecret) return '';
  return c.kind === 'EMAIL' ? ' · no password' : ' · secret not set';
}
