import { Badge, Stack, Table, Text } from '@mantine/core';

import { useFiringAlerts } from '../api/client.ts';
import { severityTone } from './severity.ts';

/** Currently firing alerts for this cluster, newest first (alerting spec). */
export function FiringPanel({ clusterId }: { clusterId: string }) {
  const firing = useFiringAlerts(clusterId);

  if (firing.isPending) {
    return (
      <Text size="sm" c="dimmed">
        Loading…
      </Text>
    );
  }

  if ((firing.data ?? []).length === 0) {
    return (
      <Stack gap={4}>
        <Text size="sm" fw={500}>
          Nothing is firing
        </Text>
        <Text size="sm" c="dimmed">
          Every enabled rule is currently OK. A rule debounces through a "pending" state for its
          configured duration before it fires here.
        </Text>
      </Stack>
    );
  }

  return (
    <Table>
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Rule</Table.Th>
          <Table.Th>Subject</Table.Th>
          <Table.Th>Severity</Table.Th>
          <Table.Th>Value</Table.Th>
          <Table.Th>Firing since</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {(firing.data ?? []).map((f) => {
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
              <Table.Td>{f.value ?? '—'}</Table.Td>
              <Table.Td>
                <Text size="xs">{new Date(f.startedAt).toISOString().replace('T', ' ').replace('.000Z', 'Z')}</Text>
              </Table.Td>
            </Table.Tr>
          );
        })}
      </Table.Tbody>
    </Table>
  );
}
