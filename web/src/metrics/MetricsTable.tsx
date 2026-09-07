import { Table, Text } from '@mantine/core';
import dayjs from 'dayjs';

import type { MetricSeries } from '../api/client.ts';
import { mergeByTimestamp } from './axis.ts';
import styles from './StatRow.module.css';

/**
 * The displayed window as rows.
 *
 * A rendered plot is not reachable by every operator, and a crosshair is a poor
 * way to read an exact value even when it is. Newest first, because the question
 * that brings someone to this page is almost always about now.
 */
export function MetricsTable({
  columns,
  format,
}: {
  columns: Array<{ name: string; label: string; series: MetricSeries | undefined }>;
  format: (name: string, value: number) => string;
}) {
  const rows = mergeByTimestamp(columns.map((c) => ({ name: c.name, series: c.series }))).reverse();

  if (rows.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        No buckets in this window.
      </Text>
    );
  }

  return (
    <Table.ScrollContainer minWidth={480} mah="24rem" type="native">
      <Table stickyHeader highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Bucket</Table.Th>
            {columns.map((c) => (
              <Table.Th key={c.name} ta="end">
                {c.label}
              </Table.Th>
            ))}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((row) => (
            <Table.Tr key={row.ts}>
              <Table.Td className={styles.value}>
                {dayjs(row.ts).format('MMM D HH:mm:ss')}
              </Table.Td>
              {columns.map((c) => (
                <Table.Td key={c.name} ta="end" className={styles.value}>
                  {row[c.name] === undefined ? '—' : format(c.name, row[c.name])}
                </Table.Td>
              ))}
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}
