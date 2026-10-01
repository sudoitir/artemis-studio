import { createElement } from 'react';

import type { Column } from '../../ui/table/index.ts';
import type { ConsumerHealthView } from './api.ts';
import { QueueLink } from './cells.tsx';
import { HealthVerdict } from './HealthVerdict.tsx';
import { formatRate, trendPhrase, verdictCopy } from './verdict.ts';

/** The verdict as the plain text a cell copies and is measured by, with its staleness. */
function verdictText(r: ConsumerHealthView): string {
  const { label } = verdictCopy(r.verdict);
  return r.stale ? `${label} · stale` : label;
}

/**
 * The consumer-health grid's columns. The verdict and the queue identify a row and are never
 * hidden; the figures that explain the verdict go next, and the address and the in-flight count are
 * the first to be hidden when the table is narrow.
 *
 * An unmeasured rate or trend reads "not measured", never 0: on this screen the two mean opposite
 * things and lead to opposite actions.
 */
export function consumerHealthColumns(): Column<ConsumerHealthView>[] {
  return [
    {
      id: 'verdict',
      header: 'Health',
      accessor: verdictText,
      cell: (r) => createElement(HealthVerdict, { row: r }),
      kind: 'status',
      priority: 'essential',
      sortKey: 'severity',
    },
    {
      id: 'queueName',
      header: 'Queue',
      accessor: (r) => r.queueName,
      cell: (r) => createElement(QueueLink, { row: r }),
      kind: 'identifier',
      priority: 'essential',
      sortKey: 'queueName',
    },
    {
      id: 'address',
      header: 'Address',
      accessor: (r) => r.address,
      kind: 'identifier',
      priority: 'low',
      sortKey: 'address',
    },
    { id: 'depth', header: 'Depth', accessor: (r) => r.depth, kind: 'number', priority: 'high', sortKey: 'depth' },
    {
      id: 'consumers',
      header: 'Consumers',
      accessor: (r) => r.consumers,
      kind: 'number',
      priority: 'high',
      sortKey: 'consumers',
    },
    {
      id: 'delivering',
      header: 'In flight',
      accessor: (r) => r.delivering,
      kind: 'number',
      priority: 'low',
      sortKey: 'delivering',
    },
    {
      id: 'ackRate',
      header: 'Acknowledged',
      accessor: (r) => formatRate(r.ackRate),
      kind: 'number',
      priority: 'high',
    },
    { id: 'trend', header: 'Trend', accessor: trendPhrase, kind: 'text', priority: 'high' },
  ];
}
