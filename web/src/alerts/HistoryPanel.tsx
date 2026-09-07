import { useState } from 'react';
import { Badge, Button, Group, Table, Text } from '@mantine/core';

import { useAlertHistory } from '../api/client.ts';
import { severityTone } from './severity.ts';
import { absoluteLabel } from '../app/time.ts';
import { useDisplayZone } from '../app/timezone.ts';

const PAGE_SIZE = 50;

/** Every past firing and resolution for this cluster, newest first (alerting spec). */
export function HistoryPanel({ clusterId }: { clusterId: string }) {
  // Absolute timestamps here read the display zone from module state, so this
  // subscribes the view to a zone change (`app/timezone.ts`).
  useDisplayZone();
  const [page, setPage] = useState(1);
  const history = useAlertHistory(clusterId, page, PAGE_SIZE);

  if (history.isPending) {
    return (
      <Text size="sm" c="dimmed">
        Loading…
      </Text>
    );
  }

  const items = history.data?.items ?? [];
  const total = history.data?.totalElements ?? 0;
  const hasMore = page * PAGE_SIZE < total;

  if (items.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        No firings recorded yet for this cluster.
      </Text>
    );
  }

  return (
    <>
      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Rule</Table.Th>
            <Table.Th>Subject</Table.Th>
            <Table.Th>Severity</Table.Th>
            <Table.Th>Started</Table.Th>
            <Table.Th>Resolved</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {items.map((f) => {
            const tone = severityTone(f.severity);
            return (
              <Table.Tr key={f.seq}>
                <Table.Td>
                  <Text size="sm">{f.ruleName}</Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm" ff="monospace">
                    {f.subjectKey}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Badge size="xs" variant="light" color={tone.color}>
                    {tone.word}
                  </Badge>
                </Table.Td>
                <Table.Td>
                  <Text size="xs">{absoluteLabel(f.startedAt)}</Text>
                </Table.Td>
                <Table.Td>
                  <Text size="xs" c={f.resolvedAt ? undefined : 'dimmed'}>
                    {f.resolvedAt ? absoluteLabel(f.resolvedAt) : 'still firing'}
                  </Text>
                </Table.Td>
              </Table.Tr>
            );
          })}
        </Table.Tbody>
      </Table>
      <Group justify="space-between" mt="sm">
        <Text size="xs" c="dimmed">
          {total} total
        </Text>
        <Group gap="xs">
          <Button size="xs" variant="default" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>
            Previous
          </Button>
          <Button size="xs" variant="default" disabled={!hasMore} onClick={() => setPage((p) => p + 1)}>
            Next
          </Button>
        </Group>
      </Group>
    </>
  );
}
