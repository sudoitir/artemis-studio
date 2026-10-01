import { createElement } from 'react';

import { absoluteLabel } from '../../kernel/time/time.ts';
import { AUTO, localZone } from '../../kernel/time/timezone.ts';
import type { Column } from '../../ui/table/index.ts';
import type { MessageSummaryView } from './api.ts';
import { BodyPreview } from './cells.tsx';

/**
 * The columns of the message browser. The enqueue time is written in the display zone `zone`, which
 * the header states, so a view builds its columns again when the zone changes.
 */
export function messageColumns(zone: string): Column<MessageSummaryView>[] {
  return [
    {
      id: 'messageId',
      header: 'Message ID',
      accessor: (m) => m.messageId,
      kind: 'identifier',
      priority: 'essential',
    },
    {
      id: 'timestamp',
      header: 'Enqueued',
      accessor: (m) => absoluteLabel(m.timestamp),
      description: `When the broker enqueued the message, in ${zone === AUTO ? localZone() : zone}`,
      kind: 'time',
      priority: 'high',
    },
    { id: 'priority', header: 'Prio', accessor: (m) => m.priority, kind: 'number', priority: 'low' },
    {
      id: 'durable',
      header: 'Durable',
      accessor: (m) => (m.durable ? 'yes' : 'no'),
      kind: 'status',
      priority: 'low',
    },
    { id: 'size', header: 'Size', accessor: (m) => m.size, kind: 'number', priority: 'high' },
    { id: 'props', header: 'Props', accessor: (m) => m.propertyCount, kind: 'number', priority: 'low' },
    {
      id: 'body',
      header: 'Body',
      accessor: (m) => m.bodyPreview ?? '',
      cell: (m) => createElement(BodyPreview, { message: m }),
      kind: 'code',
      priority: 'high',
    },
  ];
}
