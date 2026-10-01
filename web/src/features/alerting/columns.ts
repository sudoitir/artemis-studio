import { createElement, type ReactNode } from 'react';

import { absoluteLabel, elapsedLabel } from '../../kernel/time/time.ts';
import { AUTO, localZone } from '../../kernel/time/timezone.ts';
import type { Column } from '../../ui/table/index.ts';
import type { AlertDeliveryView, AlertFiringView, AlertRuleView, NotificationChannelView } from './api.ts';
import {
  ChannelActions,
  ChannelHealth,
  ConditionCell,
  DeliveryState,
  NameWithMark,
  Notification,
  RuleActions,
  RuleEnabled,
  SeverityBadge,
  type ChannelControls,
  type RuleControls,
} from './cells.tsx';
import { deliveryState, destination, kindLabel } from './channelKinds.ts';
import { deliveryWhen, missingSecretHint, ruleCondition } from './words.ts';
import { severityTone } from './severity.ts';

const zoneName = (zone: string) => (zone === AUTO ? localZone() : zone);

/** What a rules table needs from its view: the channel names and what its row controls may do. */
export interface RuleRows {
  channelNames: ReadonlyMap<string, string>;
  controls: RuleControls;
}

const channelsOf = (rule: AlertRuleView, names: ReadonlyMap<string, string>) =>
  rule.channelIds.length ? rule.channelIds.map((id) => names.get(id) ?? id).join(', ') : 'none';

/**
 * The rules' columns. The name identifies a rule; the switch and the two actions are never hidden, so a
 * rule can always be switched, edited or deleted. The channels and the debounce are the first to be
 * hidden when the table is narrow.
 */
export function ruleColumns({ channelNames, controls }: RuleRows): Column<AlertRuleView>[] {
  return [
    {
      id: 'name',
      header: 'Name',
      accessor: (r) => r.name,
      cell: (r) => createElement(NameWithMark, { name: r.name, clusterId: r.clusterId }),
      kind: 'text',
      wrap: true,
      priority: 'essential',
    },
    {
      id: 'condition',
      header: 'Condition',
      accessor: ruleCondition,
      cell: (r) => createElement(ConditionCell, { rule: r }),
      kind: 'code',
      wrap: true,
      priority: 'high',
    },
    { id: 'for', header: 'For', accessor: (r) => `${r.forSeconds}s`, kind: 'number', priority: 'low' },
    {
      id: 'severity',
      header: 'Severity',
      accessor: (r) => severityTone(r.severity).word,
      cell: (r) => createElement(SeverityBadge, { severity: r.severity }),
      kind: 'status',
      badge: true,
      priority: 'high',
    },
    {
      id: 'channels',
      header: 'Channels',
      accessor: (r) => channelsOf(r, channelNames),
      kind: 'text',
      wrap: true,
      priority: 'low',
    },
    {
      id: 'enabled',
      header: 'Enabled',
      accessor: (r) => (r.enabled ? 'on' : 'off'),
      cell: (r) => createElement(RuleEnabled, { rule: r, controls }),
      kind: 'status',
      priority: 'essential',
    },
    {
      id: 'actions',
      header: 'Actions',
      accessor: () => 'Edit Delete',
      cell: (r) => createElement(RuleActions, { rule: r, controls }),
      kind: 'status',
      wrap: true,
      priority: 'essential',
    },
  ];
}

/**
 * The firing alerts' columns. The rule and the subject identify a firing and are never hidden; the
 * severity and the time go next. Times are written in the display zone `zone`, which the header states,
 * so a view builds its columns again when the zone changes.
 */
export function firingColumns(zone: string): Column<AlertFiringView>[] {
  return [
    {
      id: 'rule',
      header: 'Rule',
      accessor: (f) => f.ruleName,
      cell: (f) => createElement(NameWithMark, { name: f.ruleName, clusterId: f.clusterId }),
      kind: 'text',
      wrap: true,
      priority: 'essential',
    },
    {
      id: 'subject',
      header: 'Subject',
      accessor: (f) => f.subjectKey,
      kind: 'code',
      wrap: true,
      priority: 'essential',
    },
    {
      id: 'severity',
      header: 'Severity',
      accessor: (f) => severityTone(f.severity).word,
      cell: (f) => createElement(SeverityBadge, { severity: f.severity }),
      kind: 'status',
      badge: true,
      priority: 'high',
    },
    { id: 'value', header: 'Value', accessor: (f) => f.value ?? '—', kind: 'number', priority: 'high' },
    {
      id: 'since',
      header: 'Firing since',
      accessor: (f) => absoluteLabel(f.startedAt),
      description: `When the alert started firing, in ${zoneName(zone)}`,
      kind: 'time',
      priority: 'high',
    },
  ];
}

/** The history's columns: the firings' columns with the resolution in place of the value. */
export function historyColumns(zone: string): Column<AlertFiringView>[] {
  return [
    ...firingColumns(zone).filter((c) => c.id !== 'value' && c.id !== 'since'),
    {
      id: 'started',
      header: 'Started',
      accessor: (f) => absoluteLabel(f.startedAt),
      description: `When the alert started firing, in ${zoneName(zone)}`,
      kind: 'time',
      priority: 'high',
    },
    {
      id: 'resolved',
      header: 'Resolved',
      accessor: (f) => (f.resolvedAt ? absoluteLabel(f.resolvedAt) : 'still firing'),
      description: `When the alert stopped firing, in ${zoneName(zone)}`,
      kind: 'time',
      priority: 'high',
    },
  ];
}

function healthText(h: NotificationChannelView['health'], now: number): string {
  if (!h) return 'never used';
  return `${deliveryState(h.lastState)} ${elapsedLabel(now - Date.parse(h.lastCreatedAt))} ago`;
}

/** What the channels table needs from its view: the clock and what its row controls may do. */
export interface ChannelRows {
  now: number;
  controls: ChannelControls;
}

/**
 * The channels' columns. The name identifies a channel and the actions are never hidden; where it
 * delivers and how its last delivery went wrap, and the kind and the rule count go first when the table
 * is narrow.
 */
export function channelColumns({ now, controls }: ChannelRows): Column<NotificationChannelView>[] {
  return [
    { id: 'name', header: 'Name', accessor: (c) => c.name, kind: 'text', wrap: true, priority: 'essential' },
    { id: 'kind', header: 'Kind', accessor: (c) => kindLabel(c.kind), kind: 'status', priority: 'low' },
    {
      id: 'destination',
      header: 'Delivers to',
      accessor: (c) => `${destination(c.kind, c.config)}${missingSecretHint(c)}`,
      kind: 'text',
      wrap: true,
      priority: 'high',
    },
    { id: 'rules', header: 'Rules', accessor: (c) => c.boundRuleCount, kind: 'number', priority: 'low' },
    {
      id: 'health',
      header: 'Last delivery',
      accessor: (c) => healthText(c.health, now),
      cell: (c) => createElement(ChannelHealth, { health: c.health, now }),
      kind: 'text',
      wrap: true,
      priority: 'high',
    },
    {
      id: 'state',
      header: 'State',
      accessor: (c) => (c.enabled ? 'enabled' : 'disabled'),
      kind: 'status',
      priority: 'high',
    },
    {
      id: 'actions',
      header: 'Actions',
      accessor: () => 'Test Log Edit Delete',
      cell: (c) => createElement(ChannelActions, { channel: c, controls }),
      kind: 'status',
      wrap: true,
      priority: 'essential',
    },
  ];
}

/**
 * The delivery log's columns. When it was queued and what it said identify a delivery; the state, with
 * when it went out or that it gave up, and the retry control are never hidden, and the attempts go
 * first when the drawer is narrow.
 */
export function deliveryColumns(
  zone: string,
  retryControl: (d: AlertDeliveryView) => ReactNode,
): Column<AlertDeliveryView>[] {
  return [
    {
      id: 'queued',
      header: 'Queued',
      accessor: (d) => absoluteLabel(d.createdAt),
      description: `When the delivery was queued, in ${zoneName(zone)}`,
      kind: 'time',
      priority: 'essential',
    },
    {
      id: 'notification',
      header: 'Notification',
      accessor: (d) => d.summary,
      cell: (d) => createElement(Notification, { delivery: d }),
      kind: 'text',
      wrap: true,
      priority: 'high',
    },
    {
      id: 'state',
      header: 'State',
      accessor: (d) => `${deliveryState(d.state)} ${deliveryWhen(d)}`,
      cell: (d) => createElement(DeliveryState, { delivery: d }),
      kind: 'text',
      wrap: true,
      priority: 'essential',
    },
    { id: 'attempts', header: 'Attempts', accessor: (d) => d.attempts, kind: 'number', priority: 'low' },
    {
      id: 'retry',
      header: 'Retry',
      accessor: () => 'Retry',
      cell: retryControl,
      kind: 'status',
      priority: 'essential',
    },
  ];
}
