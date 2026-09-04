import { Alert, Badge, Drawer, Group, Loader, Stack, Table, Text } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';

import { useRrFlow } from '../api/client.ts';
import { stateColorVar, stateLabel } from './rrState.ts';

/** The `rr_event` timeline and any captured payload for one flow, mirroring {@code MessageDetailPanel}. */
export function FlowDetail({
  clusterId,
  flowId,
  onClose,
}: {
  clusterId: string;
  flowId: string | null;
  onClose: () => void;
}) {
  const detail = useRrFlow(clusterId, flowId ?? undefined);
  const f = detail.data;

  return (
    <Drawer
      opened={flowId !== null}
      onClose={onClose}
      position="right"
      size="lg"
      title={f ? `Flow on ${f.requestAddress}` : 'Flow'}
    >
      {detail.isPending ? (
        <Loader size="sm" />
      ) : detail.isError ? (
        <Alert color="red" variant="light" title={detail.error.title}>
          {detail.error.message}
        </Alert>
      ) : f ? (
        <Stack gap="md">
          <Group gap="xs">
            <Badge variant="light" style={{ color: stateColorVar(f.state) }}>
              {stateLabel(f.state)}
            </Badge>
            <Badge variant="light" color="gray">
              {f.replyKind === 'TEMP_QUEUE' ? 'temp reply queue' : 'shared reply queue'}
            </Badge>
          </Group>

          <Table withRowBorders={false} verticalSpacing={2}>
            <Table.Tbody>
              <Table.Tr>
                <Table.Td w="35%">
                  <Text size="xs" c="dimmed">
                    Requested at
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="xs" ff="monospace">
                    {f.requestedAt}
                  </Text>
                </Table.Td>
              </Table.Tr>
              {f.repliedAt ? (
                <Table.Tr>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      Replied at
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" ff="monospace">
                      {f.repliedAt}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ) : null}
              {f.deadlineAt ? (
                <Table.Tr>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      Deadline
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" ff="monospace">
                      {f.deadlineAt}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ) : null}
              {f.latencyMs != null ? (
                <Table.Tr>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      Latency
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" ff="monospace">
                      {f.latencyMs}ms
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ) : null}
              {f.correlationId ? (
                <Table.Tr>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      Correlation id
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" ff="monospace">
                      {f.correlationId}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ) : null}
              {f.replyDestination ? (
                <Table.Tr>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      Reply destination
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" ff="monospace">
                      {f.replyDestination}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ) : null}
            </Table.Tbody>
          </Table>

          <Text size="xs" fw={600} c="dimmed">
            Timeline
          </Text>
          {f.events && f.events.length > 0 ? (
            <Stack gap="xs">
              {f.events.map((e) => (
                <Stack key={e.seq} gap={2}>
                  <Group gap="xs">
                    <Text size="xs" ff="monospace">
                      {e.ts}
                    </Text>
                    <Badge size="xs" variant="light">
                      {e.kind}
                    </Badge>
                  </Group>
                  {e.detail ? (
                    <CodeHighlight
                      code={JSON.stringify(e.detail, null, 2)}
                      language="json"
                    />
                  ) : null}
                </Stack>
              ))}
            </Stack>
          ) : (
            <Text size="xs" c="dimmed">
              No events recorded for this flow.
            </Text>
          )}
        </Stack>
      ) : null}
    </Drawer>
  );
}
