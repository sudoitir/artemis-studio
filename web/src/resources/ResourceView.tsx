import { useEffect, useState } from 'react';
import { Alert, Button, Group, Skeleton, Stack, Text, TextInput } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import {
  useAddresses,
  useConnections,
  useConsumers,
  useProducers,
  useSessions,
  type AddressView,
  type ConnectionView,
  type ConsumerView,
  type PagedView,
  type ProducerView,
  type ResourceParams,
  type SessionView,
} from '../api/client.ts';
import { VirtualTable, type GridColumn } from '../grid/VirtualTable.tsx';
import type { ApiError } from '../api/client.ts';
import type { UseQueryResult } from '@tanstack/react-query';

const PAGE_SIZE = 200;

type Kind = 'addresses' | 'consumers' | 'sessions' | 'connections' | 'producers';

interface KindConfig<T> {
  hook: (id: string, p: ResourceParams) => UseQueryResult<PagedView<T>, ApiError>;
  columns: GridColumn<T>[];
  rowKey: (row: T) => string;
  filter: string;
  noun: string;
}

const NODE_COL = <T extends { nodeName: string }>(): GridColumn<T> => ({
  id: 'node',
  header: 'Node',
  accessor: (r) => r.nodeName,
  sortKey: undefined,
  width: 140,
});

const CONFIG: {
  addresses: KindConfig<AddressView>;
  consumers: KindConfig<ConsumerView>;
  sessions: KindConfig<SessionView>;
  connections: KindConfig<ConnectionView>;
  producers: KindConfig<ProducerView>;
} = {
  addresses: {
    hook: useAddresses,
    rowKey: (r) => `${r.nodeId}:${r.name}`,
    filter: 'Filter by address',
    noun: 'address',
    columns: [
      { id: 'name', header: 'Address', accessor: (r) => r.name, sortKey: 'name' },
      { id: 'routing', header: 'Routing', accessor: (r) => r.routingTypes ?? '', width: 130 },
      { id: 'queues', header: 'Queues', accessor: (r) => r.queueCount, numeric: true, width: 100 },
      { id: 'depth', header: 'Messages', accessor: (r) => r.messageCount, numeric: true, width: 120 },
      NODE_COL<AddressView>(),
    ],
  },
  consumers: {
    hook: useConsumers,
    rowKey: (r) => `${r.nodeId}:${r.consumerId}`,
    filter: 'Filter by queue',
    noun: 'consumer',
    columns: [
      { id: 'queue', header: 'Queue', accessor: (r) => r.queueName ?? '', sortKey: 'queue' },
      { id: 'address', header: 'Address', accessor: (r) => r.address ?? '' },
      { id: 'protocol', header: 'Protocol', accessor: (r) => r.protocol ?? '', width: 100 },
      {
        id: 'delivered',
        header: 'Delivered',
        accessor: (r) => r.messagesDelivered,
        numeric: true,
        width: 110,
      },
      {
        id: 'acked',
        header: 'Acked',
        accessor: (r) => r.messagesAcknowledged,
        numeric: true,
        width: 100,
      },
      { id: 'status', header: 'Status', accessor: (r) => r.status ?? '', width: 90 },
      NODE_COL<ConsumerView>(),
    ],
  },
  sessions: {
    hook: useSessions,
    rowKey: (r) => `${r.nodeId}:${r.sessionId}`,
    filter: 'Filter by session id',
    noun: 'session',
    columns: [
      { id: 'session', header: 'Session', accessor: (r) => r.sessionId ?? '', sortKey: 'session' },
      { id: 'user', header: 'User', accessor: (r) => r.user ?? '', width: 120 },
      { id: 'conn', header: 'Connection', accessor: (r) => r.connectionId ?? '', width: 140 },
      {
        id: 'consumers',
        header: 'Consumers',
        accessor: (r) => r.consumerCount,
        numeric: true,
        width: 110,
      },
      {
        id: 'producers',
        header: 'Producers',
        accessor: (r) => r.producerCount,
        numeric: true,
        width: 110,
      },
      NODE_COL<SessionView>(),
    ],
  },
  connections: {
    hook: useConnections,
    rowKey: (r) => `${r.nodeId}:${r.connectionId}`,
    filter: 'Filter by remote address',
    noun: 'connection',
    columns: [
      {
        id: 'remote',
        header: 'Remote address',
        accessor: (r) => r.remoteAddress ?? '',
        sortKey: 'remote',
      },
      { id: 'protocol', header: 'Protocol', accessor: (r) => r.protocol ?? '', width: 100 },
      { id: 'client', header: 'Client id', accessor: (r) => r.clientId ?? '', width: 140 },
      {
        id: 'sessions',
        header: 'Sessions',
        accessor: (r) => r.sessionCount,
        numeric: true,
        width: 100,
      },
      NODE_COL<ConnectionView>(),
    ],
  },
  producers: {
    hook: useProducers,
    rowKey: (r) => `${r.nodeId}:${r.producerId}`,
    filter: 'Filter by address',
    noun: 'producer',
    columns: [
      { id: 'address', header: 'Address', accessor: (r) => r.address ?? '', sortKey: 'address' },
      { id: 'name', header: 'Name', accessor: (r) => r.name ?? '' },
      { id: 'protocol', header: 'Protocol', accessor: (r) => r.protocol ?? '', width: 100 },
      { id: 'sent', header: 'Sent', accessor: (r) => r.messagesSent, numeric: true, width: 100 },
      NODE_COL<ProducerView>(),
    ],
  },
};

/** The remaining five cross-node views, all from one column-spec-driven grid (ADR-0017). */
export function ResourceView({ kind }: { kind: Kind }) {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as { q?: string; sort?: string; page?: number };
  const navigate = useNavigate();
  const config = CONFIG[kind] as unknown as KindConfig<{ nodeName: string }>;

  const [filter, setFilter] = useState(search.q ?? '');
  const [debounced] = useDebouncedValue(filter, 250);
  const page = search.page ?? 1;

  useEffect(() => {
    if ((search.q ?? '') === debounced) return;
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({
        ...prev,
        q: debounced || undefined,
        page: undefined,
      }),
    });
  }, [debounced, navigate, search.q]);

  const query = config.hook(clusterId, {
    q: search.q,
    sort: search.sort,
    page,
    size: PAGE_SIZE,
  });

  const setSort = (sort: string | undefined) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, sort, page: undefined }),
    });
  const setPage = (next: number) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, page: next > 1 ? next : undefined }),
    });

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
          placeholder={config.filter}
          value={filter}
          onChange={(e) => setFilter(e.currentTarget.value)}
          w={280}
          size="xs"
        />
        <Text size="xs" c="dimmed">
          {total} {config.noun}
          {total === 1 ? '' : 's'} · page {page} of {lastPage}
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
          columns={config.columns}
          data={rows}
          sort={search.sort}
          onSortChange={setSort}
          rowKey={config.rowKey}
          emptyLabel={
            <Text size="sm">
              No {config.noun}s right now. This view is a live read across every serving node — one
              request per node per load.
            </Text>
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
    </Stack>
  );
}
