import type { Column } from '../../ui/table/index.ts';
import type { QueueView } from './api.ts';

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
 * Every column fits a 1280 px window beside the expanded navigation (ADR-0163), so the columns are
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
