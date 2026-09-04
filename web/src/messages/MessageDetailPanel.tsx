import { Alert, Badge, Drawer, Group, Loader, Stack, Table, Text } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';

import { useMessageDetail } from '../api/client.ts';

/**
 * Raises the per-message body/property cap. Mirrors
 * {@code BrokerXmlSnippets.forMessageBodyLimit()} on the backend — shown next to
 * a truncated body so the operator has the exact change to make (non-negotiable
 * #5). This is a per-message disclosure, not a capability gate.
 */
const RAISE_LIMIT_SNIPPET = `<address-settings>
  <address-setting match="#">
    <management-message-attribute-size-limit>-1</management-message-attribute-size-limit>
  </address-setting>
</address-settings>`;

function PropertyTable({ title, entries }: { title: string; entries: [string, unknown][] }) {
  if (entries.length === 0) return null;
  return (
    <Stack gap={4}>
      <Text size="xs" fw={600} c="dimmed">
        {title}
      </Text>
      <Table withRowBorders={false} verticalSpacing={2}>
        <Table.Tbody>
          {entries.map(([k, v]) => (
            <Table.Tr key={k}>
              <Table.Td w="40%">
                <Text size="xs" ff="monospace">
                  {k}
                </Text>
              </Table.Td>
              <Table.Td>
                <Text size="xs" ff="monospace" style={{ wordBreak: 'break-all' }}>
                  {String(v)}
                </Text>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

export function MessageDetailPanel({
  clusterId,
  queueName,
  messageId,
  node,
  filter,
  onClose,
}: {
  clusterId: string;
  queueName: string;
  messageId: string | null;
  node?: string;
  filter?: string;
  onClose: () => void;
}) {
  const detail = useMessageDetail(clusterId, queueName, messageId, node, filter);
  const m = detail.data;

  return (
    <Drawer
      opened={messageId !== null}
      onClose={onClose}
      position="right"
      size="xl"
      title={messageId ? `Message ${messageId}` : ''}
    >
      {detail.isPending ? (
        <Loader size="sm" />
      ) : detail.isError ? (
        <Alert color="red" variant="light" title={detail.error.title}>
          {detail.error.message}
        </Alert>
      ) : m ? (
        <Stack gap="md">
          <Group gap="xs">
            <Badge variant="light">type {m.type}</Badge>
            <Badge variant="light" color="gray">
              {m.durable ? 'durable' : 'non-durable'}
            </Badge>
            <Badge variant="light" color="gray">
              priority {m.priority}
            </Badge>
            <Badge variant="light" color="gray">
              {m.size} bytes
            </Badge>
          </Group>

          <Table withRowBorders={false} verticalSpacing={2}>
            <Table.Tbody>
              <Table.Tr>
                <Table.Td w="40%">
                  <Text size="xs" c="dimmed">
                    Enqueued
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="xs">
                    {m.timestamp > 0 ? new Date(m.timestamp).toISOString() : '—'}
                  </Text>
                </Table.Td>
              </Table.Tr>
              <Table.Tr>
                <Table.Td>
                  <Text size="xs" c="dimmed">
                    Expiration
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="xs">
                    {m.expiration > 0 ? new Date(m.expiration).toISOString() : 'never'}
                  </Text>
                </Table.Td>
              </Table.Tr>
              {m.groupId ? (
                <Table.Tr>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      Group
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs">{m.groupId}</Text>
                  </Table.Td>
                </Table.Tr>
              ) : null}
              {m.correlationId ? (
                <Table.Tr>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      Correlation ID
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs">{m.correlationId}</Text>
                  </Table.Td>
                </Table.Tr>
              ) : null}
              {m.userId ? (
                <Table.Tr>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      User ID
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs">{m.userId}</Text>
                  </Table.Td>
                </Table.Tr>
              ) : null}
            </Table.Tbody>
          </Table>

          <PropertyTable title="String properties" entries={Object.entries(m.stringProperties)} />
          <PropertyTable title="Integer properties" entries={Object.entries(m.intProperties)} />
          <PropertyTable title="Long properties" entries={Object.entries(m.longProperties)} />
          <PropertyTable title="Double properties" entries={Object.entries(m.doubleProperties)} />
          <PropertyTable title="Boolean properties" entries={Object.entries(m.booleanProperties)} />

          <Stack gap={4}>
            <Text size="xs" fw={600} c="dimmed">
              Body
            </Text>
            <CodeHighlight code={m.body ?? '(empty)'} language="text" />
          </Stack>

          {m.bodyTruncated ? (
            <Alert color="yellow" variant="light" title="This message is truncated">
              <Stack gap="xs">
                <Text size="sm">
                  The broker clipped this message's body and property values at{' '}
                  {m.observedLimitBytes ?? 'its'} bytes
                  (<code>management-message-attribute-size-limit</code>). To see the whole message,
                  raise the limit in <code>broker.xml</code> and re-browse:
                </Text>
                <CodeHighlight code={RAISE_LIMIT_SNIPPET} language="xml" />
                <Text size="xs" c="dimmed">
                  Faithful binary bodies arrive with the Core client in Phase 4.
                </Text>
              </Stack>
            </Alert>
          ) : null}
        </Stack>
      ) : null}
    </Drawer>
  );
}
