import { Table, Text } from '@mantine/core';

import type { FlowView } from '../api/client.ts';
import { elapsedLabel, useServerNow } from '../app/time.ts';
import { stateColorVar, stateLabel } from './rrState.ts';

/** One flow's state, address, correlation id, age, and latency (or none yet). */
export function FlowsTable({
  flows,
  onSelect,
}: {
  flows: FlowView[];
  onSelect: (flowId: string) => void;
}) {
  // Ticking, and on Studio's clock rather than the workstation's — an age is a
  // server timestamp subtracted from now, so the two must be the same clock.
  const now = useServerNow();

  if (flows.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        No request-reply flows observed yet for this filter.
      </Text>
    );
  }

  return (
    <Table.ScrollContainer minWidth={640} type="native">
      <Table stickyHeader highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>State</Table.Th>
            <Table.Th>Address</Table.Th>
            <Table.Th>Correlation id</Table.Th>
            <Table.Th>Age</Table.Th>
            <Table.Th>Latency</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {flows.map((f) => (
            <Table.Tr key={f.id} onClick={() => onSelect(f.id)} style={{ cursor: 'pointer' }}>
              <Table.Td>
                <Text size="xs" fw={600} style={{ color: stateColorVar(f.state) }}>
                  {stateLabel(f.state)}
                </Text>
              </Table.Td>
              <Table.Td>
                <Text size="xs" ff="monospace">
                  {f.requestAddress}
                </Text>
              </Table.Td>
              <Table.Td>
                <Text size="xs" ff="monospace">
                  {f.correlationId ?? '—'}
                </Text>
              </Table.Td>
              <Table.Td>
                <Text size="xs">{elapsedLabel(now - Date.parse(f.requestedAt))}</Text>
              </Table.Td>
              <Table.Td>
                <Text size="xs">{f.latencyMs != null ? `${f.latencyMs}ms` : '—'}</Text>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}
