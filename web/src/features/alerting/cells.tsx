import { elapsedLabel } from '../../kernel/time/time.ts';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { AlertDeliveryView, AlertRuleView, NotificationChannelView } from './api.ts';
import classes from './Alerting.module.css';
import { deliveryState } from './channelKinds.ts';
import { InstallationBadge } from './InstallationBadge.tsx';
import { ruleCondition, deliveryWhen } from './words.ts';
import { severityTone } from './severity.ts';

/** A severity in a word, with how much attention it needs. */
export function SeverityBadge({ severity }: Readonly<{ severity: string }>) {
  const { word, tone } = severityTone(severity);
  return <StatusBadge tone={tone}>{word}</StatusBadge>;
}

/** A rule's or firing's name, marked when it belongs to Studio itself rather than a cluster. */
export function NameWithMark({ name, clusterId }: Readonly<{ name: string; clusterId: string | null | undefined }>) {
  return (
    <span className={classes.inline}>
      {name}
      <InstallationBadge clusterId={clusterId} />
    </span>
  );
}

/** A rule's condition, with the reason it cannot fire when its metric's source is not running. */
export function ConditionCell({ rule }: Readonly<{ rule: AlertRuleView }>) {
  return (
    <span className={classes.lines}>
      <span>{ruleCondition(rule)}</span>
      {rule.sourceAvailable ? null : (
        <span className={classes.note}>
          Source unavailable: the plugin that publishes this metric is not running, so the rule cannot fire.
        </span>
      )}
    </span>
  );
}

/** How the channel's last delivery went, and its 24-hour tally. */
export function ChannelHealth({
  health: h,
  now,
}: Readonly<{ health: NotificationChannelView['health']; now: number }>) {
  if (!h) return <span className={classes.note}>never used</span>;
  const failing = h.lastState === 'DEAD';
  return (
    <span className={classes.lines}>
      <span className={failing ? classes.failed : undefined}>
        {deliveryState(h.lastState)} {elapsedLabel(now - Date.parse(h.lastCreatedAt))} ago
      </span>
      {failing && h.lastError ? <span className={`${classes.note} ${classes.failed}`}>{h.lastError}</span> : null}
      <span className={`${classes.note} ${classes.figures}`}>
        24h: {h.sentLast24h} sent, {h.failedLast24h} failed
        {h.pending > 0 ? `, ${h.pending} waiting` : ''}
      </span>
    </span>
  );
}

/** What a delivery said, with its error: history when it was sent after all, a problem when it was not. */
export function Notification({ delivery: d }: Readonly<{ delivery: AlertDeliveryView }>) {
  return (
    <span className={classes.lines}>
      <span>{d.summary}</span>
      {d.lastError ? (
        <span className={`${classes.note} ${d.state === 'SENT' ? '' : classes.failed}`}>
          {d.state === 'SENT' ? 'Earlier attempt: ' : 'Last error: '}
          {d.lastError}
        </span>
      ) : null}
    </span>
  );
}

/** A delivery's state in words, with when it went out or that it gave up. */
export function DeliveryState({ delivery: d }: Readonly<{ delivery: AlertDeliveryView }>) {
  return (
    <span className={classes.lines}>
      <span className={d.state === 'DEAD' ? classes.failed : undefined}>{deliveryState(d.state)}</span>
      <span className={`${classes.note} ${classes.figures}`}>{deliveryWhen(d)}</span>
    </span>
  );
}
