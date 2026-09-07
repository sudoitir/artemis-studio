import { useMemo, useState } from 'react';
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
  UnstyledButton,
} from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import { useDlq, type DlqQueue } from '../api/client.ts';
import { BulkActionPreview } from '../messages/BulkActionPreview.tsx';
import { Pager } from '../grid/Pager.tsx';

/**
 * How many queue cards are rendered at once.
 *
 * The read returns every dead-lettered queue across every address in one
 * payload, and a cluster in trouble has hundreds. Paging is client-side because
 * the endpoint has no page parameter; the bound is on what is drawn, which is
 * where the cost was (ADR-0056).
 */
const PAGE_SIZE = 25;

interface QueueRow {
  address: string;
  kind: string;
  queue: DlqQueue;
}

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
  const [expanded, setExpanded] = useState<string | null>(null);
  const [page, setPage] = useState(1);

  const addresses = dlq.data?.addresses;
  // Flattened so the bound is over queues rather than over addresses: one address
  // holding four hundred queues is the shape this view actually meets.
  const rows = useMemo<QueueRow[]>(
    () =>
      (addresses ?? []).flatMap((a) =>
        a.queues.map((queue) => ({ address: a.address, kind: a.kind, queue })),
      ),
    [addresses],
  );

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

  const start = (page - 1) * PAGE_SIZE;
  const visible = rows.slice(start, start + PAGE_SIZE);

  return (
    <Stack gap="md">
      <Title order={3}>Dead-letter queues</Title>

      {rows.length === 0 ? (
        <Text size="sm" c="dimmed">
          The broker's dead-letter address is{' '}
          <code>{dlq.data.addresses.map((a) => a.address).join(', ') || '—'}</code>, but no queue on
          it currently holds messages.
        </Text>
      ) : (
        <>
          <Pager
            page={page}
            pageSize={PAGE_SIZE}
            total={rows.length}
            onChange={setPage}
            label="dead-lettered queues"
          />
          {visible.map(({ address, kind, queue: q }) => {
            const key = `${address}/${q.queueName}`;
            const open = expanded === key;
            return (
              <Paper key={key} withBorder p="sm">
                <Group justify="space-between" align="flex-start">
                  <Stack gap={2}>
                    <Anchor
                      component={Link}
                      to={`/clusters/${clusterId}/queues/${encodeURIComponent(q.queueName)}/messages`}
                      size="sm"
                    >
                      {q.queueName}
                    </Anchor>
                    <Group gap="xs">
                      <Text size="xs" c="dimmed">
                        {address}
                      </Text>
                      <Badge size="xs" variant="light">
                        {kind}
                      </Badge>
                    </Group>
                    <Text size="xs" c="dimmed">
                      {q.totalDepth} message{q.totalDepth === 1 ? '' : 's'} across{' '}
                      {q.perNode.length} node{q.perNode.length === 1 ? '' : 's'}
                    </Text>
                  </Stack>
                  <Group gap="xs">
                    {/* The per-node breakdown is opened one card at a time: rendering
                        it for every card multiplies the page by the node count, which
                        is the number that grows. */}
                    <UnstyledButton
                      onClick={() => setExpanded(open ? null : key)}
                      aria-expanded={open}
                    >
                      <Text size="xs" c="dimmed" td="underline">
                        {open ? 'Hide breakdown' : 'Per-node breakdown'}
                      </Text>
                    </UnstyledButton>
                    <Button size="xs" variant="light" onClick={() => setReplay(q)}>
                      Replay all
                    </Button>
                  </Group>
                </Group>
                {open ? (
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
                ) : null}
              </Paper>
            );
          })}
        </>
      )}

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
