import { useMemo } from 'react';
import { Button, Drawer, Group, Stack } from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import { type QueueView } from './api.ts';
import { useSlot } from '../../kernel/slots.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { queueNodeColumns } from './columns.ts';
import { QueueLifecycleActions } from './QueueLifecycleActions.tsx';

type NodeCell = QueueView['perNode'][number];

const nodeKey = (cell: NodeCell) => cell.nodeId;

/**
 * Per-node breakdown for one queue row, its lifecycle actions, a jump into the message browser,
 * and whatever the enabled features add below (`queue.detail.panels`), such as its recent history.
 */
export function QueueDetailDrawer({ queue, onClose }: Readonly<{ queue: QueueView | null; onClose: () => void }>) {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const panels = useSlot('queue.detail.panels');
  const nodeColumns = useMemo(queueNodeColumns, []);

  return (
    <Drawer
      opened={queue !== null}
      onClose={onClose}
      position="right"
      size="lg"
      title={queue ? `${queue.address} / ${queue.queueName}` : ''}
    >
      {queue ? (
        <Stack gap="md">
          <Group gap="xs" justify="space-between">
            <Group gap="xs">
              <StatusBadge>{queue.routingType.toLowerCase()}</StatusBadge>
              <StatusBadge>{queue.durable ? 'durable' : 'non-durable'}</StatusBadge>
              <StatusBadge>{`${queue.nodesPresent}/${queue.nodesTotal} nodes`}</StatusBadge>
            </Group>
            <Button
              size="xs"
              variant="default"
              component={Link}
              to={`/clusters/${clusterId}/queues/${encodeURIComponent(queue.queueName)}/messages`}
              onClick={onClose}
            >
              Browse messages
            </Button>
          </Group>

          <QueueLifecycleActions clusterId={clusterId} queue={queue} onClose={onClose} />

          <Section title="Per node" headingLevel={3}>
            <DataTable
              variant="static"
              label={`${queue.queueName} per node`}
              columns={nodeColumns}
              data={queue.perNode}
              rowKey={nodeKey}
              height={{ maxRows: 8 }}
              empty={
                <EmptyState
                  kind="empty"
                  title="No node reported this queue"
                  description="The last scrape returned no per-node figures for it."
                />
              }
            />
          </Section>

          {panels.map(({ id, Component }) => (
            <Component key={id} clusterId={clusterId} queueName={queue.queueName} onClose={onClose} />
          ))}
        </Stack>
      ) : null}
    </Drawer>
  );
}
