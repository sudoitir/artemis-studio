import { createElement } from 'react';

import { absoluteLabel } from '../../kernel/time/time.ts';
import { AUTO, localZone } from '../../kernel/time/timezone.ts';
import type { Column } from '../../ui/table/index.ts';
import type { SqlRowView } from './api.ts';
import { BodyCell, SourceBadge, VerifyCell } from './cells.tsx';
import { COLUMN_LABELS, sourceOf } from './resultColumns.ts';

/**
 * Every column the result grid can show, in its default order. The queue, the message and the node a row came from
 * identify it and are never hidden; when and what it held go next, and the figures and the check on
 * the broker are the first to be hidden when the table is narrow. The time is written in the display
 * zone `zone`, which the header states, so a view builds the columns again when the zone changes.
 */
export function resultColumns(clusterId: string, zone: string): Column<SqlRowView>[] {
  return [
    {
      id: 'source',
      header: COLUMN_LABELS.source,
      accessor: (r) => sourceOf(r).word,
      cell: (r) => createElement(SourceBadge, { row: r }),
      kind: 'status',
      badge: true,
      priority: 'high',
    },
    {
      id: 'node',
      header: COLUMN_LABELS.node,
      accessor: (r) => r.nodeName ?? '',
      kind: 'identifier',
      priority: 'essential',
    },
    {
      id: 'queue',
      header: COLUMN_LABELS.queue,
      accessor: (r) => r.queueName ?? '',
      kind: 'identifier',
      priority: 'essential',
    },
    {
      id: 'messageId',
      header: COLUMN_LABELS.messageId,
      accessor: (r) => r.messageId ?? '',
      kind: 'identifier',
      priority: 'essential',
    },
    {
      id: 'timestamp',
      header: COLUMN_LABELS.timestamp,
      accessor: (r) => absoluteLabel(r.timestamp),
      description: `When the message was enqueued, in ${zone === AUTO ? localZone() : zone}`,
      kind: 'time',
      priority: 'high',
    },
    {
      id: 'priority',
      header: COLUMN_LABELS.priority,
      accessor: (r) => r.priority ?? 0,
      kind: 'number',
      priority: 'low',
    },
    { id: 'size', header: COLUMN_LABELS.size, accessor: (r) => r.size ?? 0, kind: 'number', priority: 'low' },
    {
      id: 'body',
      header: COLUMN_LABELS.body,
      accessor: (r) => r.body ?? '',
      cell: (r) => createElement(BodyCell, { row: r }),
      kind: 'code',
      priority: 'high',
    },
    {
      id: 'verify',
      header: COLUMN_LABELS.verify,
      accessor: () => '',
      cell: (r) => createElement(VerifyCell, { clusterId, row: r }),
      kind: 'status',
      // The check's answers ("still there", the reason it could not ask) are wider than the header.
      min: 14,
      priority: 'low',
    },
  ];
}
