import { useEffect, useState } from 'react';
import { Alert, Button, Group, Skeleton, Stack, Text, TextInput } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { useQueues, type QueueView } from '../api/client.ts';
import { VirtualTable, type GridColumn } from '../grid/VirtualTable.tsx';
import { QueueDetailDrawer } from './QueueDetailDrawer.tsx';

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
  const lastPage = Math.max(1, Math.ceil(total / PAGE_SIZE));

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
        <Text size="xs" c="dimmed">
          {total} queue{total === 1 ? '' : 's'} · page {page} of {lastPage}
        </Text>
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
            <Stack gap={4}>
              <Text fw={600}>No queues yet</Text>
              <Text size="sm">
                A queue is where messages wait for a consumer. Studio fills this grid from each
                broker's <code>listQueues</code>; produce to an address or create a queue and it
                appears here within a scrape tick.
              </Text>
            </Stack>
          }
        />
      )}

      {lastPage > 1 ? (
        <Group justify="flex-end" gap="xs">
          <Button size="xs" variant="default" disabled={page <= 1} onClick={() => setPage(page - 1)}>
            Previous
          </Button>
          <Button
            size="xs"
            variant="default"
            disabled={page >= lastPage}
            onClick={() => setPage(page + 1)}
          >
            Next
          </Button>
        </Group>
      ) : null}

      <QueueDetailDrawer queue={selected} onClose={() => setSelected(null)} />
    </Stack>
  );
}
