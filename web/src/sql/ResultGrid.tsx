import { useMemo } from 'react';
import { Badge, Group, Text } from '@mantine/core';

import type { SqlRowView } from '../api/client.ts';
import { VirtualTable, type GridColumn } from '../grid/VirtualTable.tsx';
import { VerifyOnBroker } from './VerifyOnBroker.tsx';
import { rowKey } from './useSqlTail.ts';
import classes from './ResultGrid.module.css';

function ts(ms?: number): string {
  return ms && ms > 0 ? new Date(ms).toISOString().replace('T', ' ').replace('.000Z', 'Z') : '—';
}

function columnsFor(clusterId: string): GridColumn<SqlRowView>[] {
  return [
    {
      id: 'source',
      header: 'Source',
      accessor: (r) => r.source ?? '',
      width: 90,
      cell: (r) =>
        r.source === 'INDEX' ? (
          <Badge
            size="xs"
            variant="light"
            color="gray"
            title="Captured by the index; it may have been consumed since"
          >
            indexed
          </Badge>
        ) : (
          <Badge size="xs" variant="default" title="Read from the live broker just now">
            live
          </Badge>
        ),
    },
    { id: 'node', header: 'Node', accessor: (r) => r.nodeName ?? '', width: 150 },
    { id: 'queue', header: 'Queue', accessor: (r) => r.queueName ?? '' },
    { id: 'messageId', header: 'Message ID', accessor: (r) => r.messageId ?? '', width: 150 },
    { id: 'timestamp', header: 'Enqueued', accessor: (r) => ts(r.timestamp), width: 200 },
    { id: 'priority', header: 'Prio', accessor: (r) => r.priority ?? 0, numeric: true, width: 70 },
    { id: 'size', header: 'Size', accessor: (r) => r.size ?? 0, numeric: true, width: 90 },
    {
      id: 'body',
      header: 'Body',
      accessor: (r) => r.body ?? '',
      cell: (r) => (
        <Group gap={6} wrap="nowrap">
          <Text size="xs" truncate>
            {r.body ?? (
              <Text span c="dimmed">
                (empty)
              </Text>
            )}
          </Text>
          {r.bodyTruncated ? (
            <Badge size="xs" color="yellow" variant="light" title="Cut by the management channel">
              truncated
            </Badge>
          ) : null}
        </Group>
      ),
    },
    {
      id: 'verify',
      header: 'On broker',
      accessor: () => '',
      width: 120,
      // Only an indexed row raises the question. A live row was read from the
      // broker moments ago, so offering to re-ask would be theatre.
      cell: (r) => (r.source === 'INDEX' ? <VerifyOnBroker clusterId={clusterId} row={r} /> : null),
    },
  ];
}

/**
 * The result set, in the console's existing virtualised grid — same paging,
 * sorting and node attribution as every other tabular view.
 *
 * <p>The leading column is where the row came from, not what it says: a live row
 * and an indexed row mean different things, and which one an operator is looking
 * at has to be answerable without opening it.
 */
export function ResultGrid({
  clusterId,
  rows,
  onOpen,
  emptyLabel,
  freshKeys,
}: {
  clusterId: string;
  rows: SqlRowView[];
  onOpen: (row: SqlRowView) => void;
  emptyLabel: React.ReactNode;
  /** Keys the live tail delivered in the last few seconds. */
  freshKeys?: ReadonlySet<string>;
}) {
  const columns = useMemo(() => columnsFor(clusterId), [clusterId]);
  return (
    <VirtualTable
      columns={columns}
      data={rows}
      rowKey={rowKey}
      onRowClick={onOpen}
      emptyLabel={emptyLabel}
      rowClassName={(r) => (freshKeys?.has(rowKey(r)) ? classes.fresh : undefined)}
    />
  );
}
