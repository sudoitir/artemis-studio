import { useEffect, useState } from 'react';
import { Alert, Button, Group, Skeleton, Stack, Text, TextInput } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { useCluster, useQueues, type QueueView } from '../api/client.ts';
import { VirtualTable, type GridColumn } from '../grid/VirtualTable.tsx';
import { Pager } from '../grid/Pager.tsx';
import { QueueDetailDrawer } from './QueueDetailDrawer.tsx';
import { CreateQueueForm } from './CreateQueueForm.tsx';
import { useCan } from '../auth/useCan.ts';

const PAGE_SIZE = 200;

const columns: GridColumn<QueueView>[] = [
  { id: 'address', header: 'Address', accessor: (r) => r.address, sortKey: 'address' },
  { id: 'queueName', header: 'Queue', accessor: (r) => r.queueName, sortKey: 'queueName' },
  { id: 'routingType', header: 'Type', accessor: (r) => r.routingType, width: 96 },
  {
    id: 'depth',
    header: 'Depth',
    accessor: (r) => r.totalMessageCount,
    numeric: true,
    sortKey: 'depth',
    width: 110,
  },
  {
    id: 'consumers',
    header: 'Consumers',
    accessor: (r) => r.totalConsumerCount,
    numeric: true,
    sortKey: 'consumers',
    width: 110,
  },
  {
    id: 'delivering',
    header: 'Delivering',
    accessor: (r) => r.totalDeliveringCount,
    numeric: true,
    sortKey: 'delivering',
    width: 110,
  },
  {
    id: 'scheduled',
    header: 'Scheduled',
    accessor: (r) => r.totalScheduledCount,
    numeric: true,
    sortKey: 'scheduled',
    width: 110,
  },
  {
    id: 'durable',
    header: 'Durable',
    accessor: (r) => r.durable,
    cell: (r) => (r.durable ? 'yes' : 'no'),
    width: 90,
  },
  {
    // A paused queue looks identical to an idle one by depth alone — it has a
    // backlog and no throughput, which is also what a broken consumer looks
    // like. Carried in words, and blank when there is nothing to say, so a
    // healthy grid stays quiet.
    id: 'paused',
    header: 'State',
    accessor: (r) => r.paused,
    cell: (r) =>
      r.paused
        ? r.perNode.every((n) => n.paused)
          ? 'paused'
          : 'paused on some nodes'
        : '',
    width: 150,
  },
  {
    id: 'nodes',
    header: 'Nodes',
    accessor: (r) => `${r.nodesPresent}/${r.nodesTotal}`,
    numeric: true,
    width: 90,
  },
];

/**
 * The headline view: every queue across every node, in one virtualized grid.
 * Sort, filter and page are URL-owned (non-negotiable #9); the grid is
 * server-driven through `useQueues`.
 */
export function QueuesView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as { q?: string; sort?: string; page?: number };
  const navigate = useNavigate();

  const [filter, setFilter] = useState(search.q ?? '');
  const [debounced] = useDebouncedValue(filter, 250);
  const page = search.page ?? 1;

  useEffect(() => {
    if ((search.q ?? '') === debounced) return;
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, q: debounced || undefined, page: undefined }),
    });
  }, [debounced, navigate, search.q]);

  const query = useQueues(clusterId, {
    q: search.q,
    sort: search.sort,
    page,
    size: PAGE_SIZE,
  });

  const [selected, setSelected] = useState<QueueView | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const { can } = useCan();
  const mayCreate = can('queue:create', clusterId);

  // An empty grid has three quite different causes, and presenting an absence as
  // a fact is the one that misleads: a node Studio could not reach contributes no
  // rows, which looks exactly like a cluster with no queues.
  const cluster = useCluster(clusterId);
  const unreachable = (cluster.data?.topology.nodes ?? [])
    .flatMap((n) => n.endpoints)
    .filter((e) => e.lastError)
    .map((e) => e.name);

  const setSort = (sort: string | undefined) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, sort, page: undefined }) });
  const setPage = (next: number) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, page: next > 1 ? next : undefined }) });

  if (query.isError) {
    return (
      <Alert color="red" variant="light" title={query.error.title}>
        {query.error.message}
      </Alert>
    );
  }

  const rows = query.data?.data ?? [];
  const total = query.data?.count ?? 0;

  return (
    <Stack gap="sm">
      <Group justify="space-between">
        <TextInput
          placeholder="Filter by queue or address"
          value={filter}
          onChange={(e) => setFilter(e.currentTarget.value)}
          w={280}
          size="xs"
        />
        <Group gap="xs">
          {/* The count and position live in the pager, stated once. */}
          {mayCreate ? (
            <Button size="xs" onClick={() => setCreateOpen(true)}>
              New queue
            </Button>
          ) : null}
        </Group>
      </Group>

      {query.isPending && rows.length === 0 ? (
        <Stack gap={4}>
          {Array.from({ length: 12 }).map((_, i) => (
            <Skeleton key={i} height={30} />
          ))}
        </Stack>
      ) : (
        <VirtualTable
          columns={columns}
          data={rows}
          sort={search.sort}
          onSortChange={setSort}
          onRowClick={setSelected}
          rowKey={(r) => `${r.address}::${r.queueName}::${r.routingType}`}
          emptyLabel={
            search.q ? (
              <Stack gap={4} align="flex-start">
                <Text fw={600}>No queue matches "{search.q}"</Text>
                <Text size="sm">
                  There may still be queues on this cluster — none of them match this filter.
                </Text>
                <Button
                  size="xs"
                  variant="light"
                  onClick={() => {
                    setFilter('');
                    navigate({
                      to: '.',
                      search: (prev: Record<string, unknown>) => ({
                        ...prev,
                        q: undefined,
                        page: undefined,
                      }),
                    });
                  }}
                >
                  Clear the filter
                </Button>
              </Stack>
            ) : unreachable.length > 0 ? (
              <Stack gap={4} align="flex-start">
                <Text fw={600}>
                  {unreachable.length === 1
                    ? `${unreachable[0]} could not be reached`
                    : `${unreachable.length} nodes could not be reached`}
                </Text>
                <Text size="sm">
                  There may be queues here that Studio cannot currently see —
                  {unreachable.length === 1 ? ' this node' : ' these nodes'} did not answer the
                  last scrape, so this is an incomplete view rather than an empty cluster.
                  {unreachable.length > 1 ? ` (${unreachable.join(', ')})` : ''}
                </Text>
              </Stack>
            ) : (
              <Stack gap={4} align="flex-start">
                <Text fw={600}>No queues yet</Text>
                <Text size="sm">
                  A queue is where messages wait for a consumer. Studio fills this grid from each
                  broker's <code>listQueues</code>; produce to an address or create a queue and it
                  appears here within a scrape tick.
                </Text>
                {mayCreate ? (
                  <Button size="xs" variant="light" onClick={() => setCreateOpen(true)}>
                    Create the first queue
                  </Button>
                ) : null}
              </Stack>
            )
          }
        />
      )}

      <Pager
        page={page}
        pageSize={PAGE_SIZE}
        total={total}
        onChange={setPage}
        label="queues"
      />

      <QueueDetailDrawer queue={selected} onClose={() => setSelected(null)} />
      <CreateQueueForm
        clusterId={clusterId}
        opened={createOpen}
        onClose={() => setCreateOpen(false)}
      />
    </Stack>
  );
}
