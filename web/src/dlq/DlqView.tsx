import { useState } from 'react';
import {
  Alert,
  Anchor,
  Badge,
  Button,
  Group,
  Loader,
  Paper,
  Stack,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import { useDlq, type DlqQueue } from '../api/client.ts';
import { BulkActionPreview } from '../messages/BulkActionPreview.tsx';

/**
 * Dead-letter / expiry management (ADR-0021, D8). Addresses come from the
 * broker's own settings — when that read fails the view says exactly that and
 * infers nothing. "Replay all" runs a by-selector RETRY through the shared
 * preview + cap gate.
 */
export function DlqView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const dlq = useDlq(clusterId);
  const [replay, setReplay] = useState<DlqQueue | null>(null);

  if (dlq.isPending) return <Loader size="sm" />;
  if (dlq.isError) {
    return (
      <Alert color="red" variant="light" title={dlq.error.title}>
        {dlq.error.message}
      </Alert>
    );
  }

  if (!dlq.data.settingsAvailable) {
    return (
      <Stack gap="sm">
        <Title order={3}>Dead-letter queues</Title>
        <Alert color="yellow" variant="light" title="Dead-letter configuration unavailable">
          Studio could not read this broker's address settings, so it will not guess which queues
          are dead-letter queues from their names. Grant the connection management-read access, or
          check <code>getAddressSettingsAsJSON</code> is permitted, then reload.
        </Alert>
      </Stack>
    );
  }

  const empty = dlq.data.addresses.every((a) => a.queues.length === 0);

  return (
    <Stack gap="md">
      <Title order={3}>Dead-letter queues</Title>

      {empty ? (
        <Text size="sm" c="dimmed">
          The broker's dead-letter address is{' '}
          <code>{dlq.data.addresses.map((a) => a.address).join(', ') || '—'}</code>, but no queue on
          it currently holds messages.
        </Text>
      ) : null}

      {dlq.data.addresses.map((addr) => (
        <Stack key={addr.address} gap="xs">
          <Group gap="xs">
            <Text fw={600}>{addr.address}</Text>
            <Badge size="xs" variant="light">
              {addr.kind}
            </Badge>
          </Group>

          {addr.queues.length === 0 ? (
            <Text size="xs" c="dimmed">
              No queues with messages.
            </Text>
          ) : (
            addr.queues.map((q) => (
              <Paper key={q.queueName} withBorder p="sm">
                <Group justify="space-between" align="flex-start">
                  <Stack gap={2}>
                    <Anchor
                      component={Link}
                      to={`/clusters/${clusterId}/queues/${encodeURIComponent(q.queueName)}/messages`}
                      size="sm"
                    >
                      {q.queueName}
                    </Anchor>
                    <Text size="xs" c="dimmed">
                      {q.totalDepth} message{q.totalDepth === 1 ? '' : 's'}
                    </Text>
                  </Stack>
                  <Button size="xs" variant="light" onClick={() => setReplay(q)}>
                    Replay all
                  </Button>
                </Group>
                <Table mt="xs" withRowBorders={false} verticalSpacing={2}>
                  <Table.Tbody>
                    {q.perNode.map((n) => (
                      <Table.Tr key={n.nodeId}>
                        <Table.Td>
                          <Text size="xs">{n.nodeName}</Text>
                        </Table.Td>
                        <Table.Td ta="end">
                          <Text size="xs">{n.depth}</Text>
                        </Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </Paper>
            ))
          )}
        </Stack>
      ))}

      {replay ? (
        <BulkActionPreview
          clusterId={clusterId}
          queueName={replay.queueName}
          action="retry"
          opened={replay !== null}
          onClose={() => setReplay(null)}
          onDone={() => setReplay(null)}
        />
      ) : null}
    </Stack>
  );
}
