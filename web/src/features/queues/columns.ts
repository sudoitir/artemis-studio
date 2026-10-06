import { createElement } from 'react';

import type { Column } from '../../ui/table/index.ts';
import type { QueueView } from './api.ts';
import { OwnerChip } from '../../kernel/auth/OwnerChip.tsx';
import { StaleBadge } from './cells.tsx';

type NodeCell = QueueView['perNode'][number];

/** The state cell: blank while running, so a healthy grid stays quiet. */
export function pausedLabel(r: QueueView): string {
  if (!r.paused) return '';
  return r.perNode.every((n) => n.paused) ? 'paused' : 'paused on some nodes';
}

/**
 * The queues grid's columns. The address and the queue name identify a row and are never hidden;
 * depth, consumers and the state go next, and the secondary counts are the first to be hidden when
 * the table is narrow.
 *
 * Every column fits a 1280 px window beside the expanded navigation (ADR-0164), so the columns are
 * compact rather than hidden: a count is as wide as its figures, its header a short label with the full
 * name on hover, and a routing type is in lower case, the way the address picker writes it.
 */
export function queueColumns(): Column<QueueView>[] {
  return [
    {
      id: 'address',
      header: 'Address',
      accessor: (r) => r.address,
      kind: 'identifier',
      priority: 'essential',
      sortKey: 'address',
    },
    {
      id: 'queueName',
      header: 'Queue',
      accessor: (r) => r.queueName,
      kind: 'identifier',
      priority: 'essential',
      sortKey: 'queueName',
    },
    {
      id: 'owner',
      header: 'Owner',
      description: 'The team whose patterns own this queue',
      accessor: (r) => r.ownerTeam?.name ?? 'No owner',
      cell: (r) => createElement(OwnerChip, { team: r.ownerTeam }),
      kind: 'status',
      badge: true,
      priority: 'high',
    },
    {
      id: 'routingType',
      header: 'Type',
      accessor: (r) => r.routingType.toLowerCase(),
      kind: 'status',
      priority: 'low',
      description: 'Routing type: anycast delivers each message to one consumer, multicast to all of them',
    },
    {
      id: 'depth',
      header: 'Depth',
      description: 'Messages waiting in the queue, on every node',
      accessor: (r) => r.totalMessageCount,
      kind: 'number',
      priority: 'high',
      sortKey: 'depth',
    },
    {
      id: 'consumers',
      header: 'Consumers',
      short: 'Cons.',
      description: 'Consumers attached, on every node',
      accessor: (r) => r.totalConsumerCount,
      kind: 'number',
      priority: 'high',
      sortKey: 'consumers',
    },
    {
      id: 'delivering',
      header: 'Delivering',
      short: 'Deliv.',
      description: 'Messages being delivered to consumers now',
      accessor: (r) => r.totalDeliveringCount,
      kind: 'number',
      priority: 'low',
      sortKey: 'delivering',
    },
    {
      id: 'scheduled',
      header: 'Scheduled',
      short: 'Sched.',
      description: 'Messages held for a later delivery time',
      accessor: (r) => r.totalScheduledCount,
      kind: 'number',
      priority: 'low',
      sortKey: 'scheduled',
    },
    {
      id: 'durable',
      header: 'Durable',
      accessor: (r) => (r.durable ? 'yes' : 'no'),
      kind: 'status',
      priority: 'low',
    },
    {
      // A paused queue looks identical to an idle one by depth alone: it has a backlog and no
      // throughput, which is also what a broken consumer looks like. Carried in words, and blank
      // when there is nothing to say, so a healthy grid stays quiet.
      id: 'paused',
      header: 'State',
      accessor: pausedLabel,
      kind: 'status',
      priority: 'high',
    },
    {
      id: 'nodes',
      header: 'Nodes',
      accessor: (r) => `${r.nodesPresent}/${r.nodesTotal}`,
      kind: 'number',
      priority: 'high',
    },
  ];
}

/**
 * The per-node breakdown of one queue, in the drawer. The node is never hidden; a node whose figures
 * are from an older scrape says so in words, in a column of its own.
 */
export function queueNodeColumns(): Column<NodeCell>[] {
  return [
    { id: 'node', header: 'Node', accessor: (n) => n.nodeName, kind: 'identifier', priority: 'essential' },
    { id: 'depth', header: 'Depth', accessor: (n) => n.messageCount, kind: 'number', priority: 'high' },
    { id: 'consumers', header: 'Consumers', accessor: (n) => n.consumerCount, kind: 'number', priority: 'high' },
    { id: 'delivering', header: 'Delivering', accessor: (n) => n.deliveringCount, kind: 'number', priority: 'high' },
    { id: 'scheduled', header: 'Scheduled', accessor: (n) => n.scheduledCount, kind: 'number', priority: 'high' },
    {
      id: 'freshness',
      header: 'Figures',
      accessor: (n) => (n.stale ? 'stale' : 'current'),
      cell: (n) => (n.stale ? createElement(StaleBadge) : 'current'),
      kind: 'status',
      priority: 'high',
      badge: true,
    },
  ];
}
