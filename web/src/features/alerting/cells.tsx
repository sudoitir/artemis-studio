import { ActionIcon, Switch } from '@mantine/core';
import { IconHistory, IconPencil, IconSend, IconTrash } from '@tabler/icons-react';

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

/** What the rules table's row controls need from their view: what is allowed and busy right now, and what a click does. */
export interface RuleControls {
  canWrite: boolean;
  /** The rule being saved, whose switch is locked until the write settles. */
  savingId: string | undefined;
  onToggle: (rule: AlertRuleView) => void;
  onEdit: (rule: AlertRuleView) => void;
  onDelete: (rule: AlertRuleView) => void;
}

/** The enabled switch: gated, busy while saving and announced by the view. */
export function RuleEnabled({ rule, controls }: Readonly<{ rule: AlertRuleView; controls: RuleControls }>) {
  return (
    <Switch
      size="sm"
      checked={rule.enabled}
      disabled={!controls.canWrite || controls.savingId === rule.id}
      aria-label={`${rule.enabled ? 'Disable' : 'Enable'} ${rule.name}`}
      onChange={() => controls.onToggle(rule)}
    />
  );
}

/** Edit and Delete: gated and confirmed by the view. */
export function RuleActions({ rule, controls }: Readonly<{ rule: AlertRuleView; controls: RuleControls }>) {
  return (
    <span className={classes.controls}>
      <ActionIcon
        variant="subtle"
        disabled={!controls.canWrite}
        onClick={() => controls.onEdit(rule)}
        aria-label={`Edit ${rule.name}`}
      >
        <IconPencil size="1rem" aria-hidden />
      </ActionIcon>
      <ActionIcon
        variant="subtle"
        disabled={!controls.canWrite}
        onClick={() => controls.onDelete(rule)}
        aria-label={`Delete ${rule.name}`}
      >
        <IconTrash size="1rem" aria-hidden />
      </ActionIcon>
    </span>
  );
}

/** What the channels table's row controls need from their view. */
export interface ChannelControls {
  canWrite: boolean;
  /** The channel whose test is running, or undefined; every other test is locked meanwhile. */
  testingId: string | undefined;
  onTest: (channel: NotificationChannelView) => void;
  onLog: (channel: NotificationChannelView) => void;
  onEdit: (channel: NotificationChannelView) => void;
  onDelete: (channel: NotificationChannelView) => void;
}

/** Test, delivery log, edit and delete: gated, busy while running and announced by the view. */
export function ChannelActions({
  channel,
  controls,
}: Readonly<{ channel: NotificationChannelView; controls: ChannelControls }>) {
  const { canWrite, testingId } = controls;
  return (
    <span className={classes.controls}>
      <ActionIcon
        variant="subtle"
        onClick={() => controls.onTest(channel)}
        loading={testingId === channel.id}
        disabled={!canWrite || (testingId !== undefined && testingId !== channel.id)}
        aria-label={`Send test notification to ${channel.name}`}
      >
        <IconSend size="1rem" aria-hidden />
      </ActionIcon>
      <ActionIcon
        variant="subtle"
        onClick={() => controls.onLog(channel)}
        aria-label={`Delivery log of ${channel.name}`}
      >
        <IconHistory size="1rem" aria-hidden />
      </ActionIcon>
      <ActionIcon
        variant="subtle"
        onClick={() => controls.onEdit(channel)}
        disabled={!canWrite}
        aria-label={`Edit ${channel.name}`}
      >
        <IconPencil size="1rem" aria-hidden />
      </ActionIcon>
      <ActionIcon
        variant="subtle"
        onClick={() => controls.onDelete(channel)}
        disabled={!canWrite}
        aria-label={`Delete ${channel.name}`}
      >
        <IconTrash size="1rem" aria-hidden />
      </ActionIcon>
    </span>
  );
}
