import { useEffect, useState, useRef } from 'react';
import { Alert, Group, Skeleton, Stack, Text, TextInput } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { useAddresses, useConnections, useConsumers, useProducers, useSessions, type AddressView, type ConnectionView, type ConsumerView, type ProducerView, type SessionView } from './api.ts';
import { type PagedView, type ResourceParams } from '../../kernel/api/paging.ts';
import { VirtualTable, type GridColumn } from '../../ui/VirtualTable.tsx';
import { CloseAddressConsumersAction, CloseConnectionAction } from './CloseConnection.tsx';
import { Pager } from '../../ui/Pager.tsx';
import { ResourceActions } from '../../kernel/actions/ResourceActions.tsx';
import { ResourceLink } from '../../kernel/actions/ResourceLink.tsx';
import type { ActionKind, ActionTargets } from '../../kernel/actions/types.ts';
import type { ApiError } from '../../kernel/api/request.ts';
import type { UseQueryResult } from '@tanstack/react-query';
import { useFilterShortcut } from '../../kernel/keyboard/filterShortcut.ts';

const PAGE_SIZE = 200;

type Kind = 'addresses' | 'consumers' | 'sessions' | 'connections' | 'producers';

/** What a row action needs that the row itself does not carry. */
interface RowContext {
  clusterId: string;
  /** When the rows on screen were fetched, in epoch ms. A close depends on it. */
  fetchedAt: number | null;
}

/**
 * A value that names another resource, as a link to it (ADR-0107) — or as plain text when the
 * feature presenting it is disabled, or when the broker gave no value.
 */
function linked(
  kind: 'queue' | 'address' | 'connection' | 'session',
  value: string | null | undefined,
): React.ReactNode {
  if (!value) return '';
  const target = (
    kind === 'queue'
      ? { queueName: value }
      : kind === 'address'
        ? { address: value }
        : kind === 'connection'
          ? { connectionId: value, nodeId: '', nodeName: '' }
          : { sessionId: value, nodeId: '', nodeName: '' }
  ) as never;
  return (
    <ResourceLink kind={kind} target={target}>
      {value}
    </ResourceLink>
  );
}

interface KindConfig<T> {
  hook: (id: string, p: ResourceParams) => UseQueryResult<PagedView<T>, ApiError>;
  columns: GridColumn<T>[];
  rowKey: (row: T) => string;
  filter: string;
  noun: string;
  /**
   * The action this row's finding implies, rendered in a trailing column. Kept
   * beside the row rather than behind a selection or a detail pane, so the thing
   * an operator has just been told about and the verb that acts on it are one
   * click apart.
   */
  action?: (row: T, ctx: RowContext) => React.ReactNode;
  /** The row's menu (ADR-0107): which resource it is, and how the row names itself. */
  menu: RowMenuConfig<T>;
}

/** What a grid row's action menu is about. */
interface RowMenuConfig<T> {
  kind: ActionKind;
  target: (row: T) => ActionTargets[ActionKind];
  label: (row: T) => string;
}

/** The trailing action column. Fixed width; the action names itself, the header does not. */
const ACTION_COL = <T,>(
  render: (row: T, ctx: RowContext) => React.ReactNode,
  ctx: RowContext,
): GridColumn<T> => ({
  id: 'action',
  header: 'Action',
  accessor: () => '',
  cell: (row) => render(row, ctx),
  width: 90,
});

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
    filter: 'Address name',
    noun: 'address',
    menu: {
      kind: 'address',
      target: (r) => ({ address: r.name, snapshot: r }),
      label: (r) => r.name,
    },
    columns: [
      { id: 'name', header: 'Address', accessor: (r) => r.name, sortKey: 'name' },
      { id: 'routing', header: 'Routing', accessor: (r) => r.routingTypes ?? '', width: 130 },
      { id: 'queues', header: 'Queues', accessor: (r) => r.queueCount, numeric: true, width: 100 },
      { id: 'depth', header: 'Messages', accessor: (r) => r.messageCount, numeric: true, width: 120 },
      NODE_COL<AddressView>(),
    ],
    action: (row, ctx) => <CloseAddressConsumersAction clusterId={ctx.clusterId} address={row.name} />,
  },
  consumers: {
    hook: useConsumers,
    rowKey: (r) => `${r.nodeId}:${r.consumerId}`,
    filter: 'Queue name or session id',
    noun: 'consumer',
    menu: {
      kind: 'consumer',
      target: (r) => ({
        nodeId: r.nodeId,
        nodeName: r.nodeName,
        consumerId: r.consumerId ?? '',
        queueName: r.queueName,
        sessionId: r.sessionId,
        snapshot: r,
      }),
      label: (r) => `${r.queueName ?? 'consumer'} (${r.consumerId ?? 'no id'})`,
    },
    columns: [
      {
        id: 'queue',
        header: 'Queue',
        accessor: (r) => r.queueName ?? '',
        cell: (r) => linked('queue', r.queueName),
        sortKey: 'queue',
      },
      { id: 'address', header: 'Address', accessor: (r) => r.address ?? '', cell: (r) => linked('address', r.address) },
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
    action: (row, ctx) => (
      <CloseConnectionAction
        clusterId={ctx.clusterId}
        kind="consumer"
        nodeId={row.nodeId}
        nodeName={row.nodeName}
        targetId={row.consumerId ?? ''}
        rowLabel={row.queueName ?? row.consumerId ?? ''}
        fetchedAt={ctx.fetchedAt}
      />
    ),
  },
  sessions: {
    hook: useSessions,
    rowKey: (r) => `${r.nodeId}:${r.sessionId}`,
    filter: 'Session id, connection id or user',
    noun: 'session',
    menu: {
      kind: 'session',
      target: (r) => ({
        nodeId: r.nodeId,
        nodeName: r.nodeName,
        sessionId: r.sessionId ?? '',
        connectionId: r.connectionId,
        snapshot: r,
      }),
      label: (r) => r.sessionId ?? 'session',
    },
    columns: [
      { id: 'session', header: 'Session', accessor: (r) => r.sessionId ?? '', sortKey: 'session' },
      { id: 'user', header: 'User', accessor: (r) => r.user ?? '', width: 120 },
      {
        id: 'conn',
        header: 'Connection',
        accessor: (r) => r.connectionId ?? '',
        cell: (r) => linked('connection', r.connectionId),
        width: 140,
      },
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
    action: (row, ctx) => (
      <CloseConnectionAction
        clusterId={ctx.clusterId}
        kind="session"
        nodeId={row.nodeId}
        nodeName={row.nodeName}
        targetId={row.sessionId ?? ''}
        rowLabel={row.user ?? row.sessionId ?? ''}
        fetchedAt={ctx.fetchedAt}
      />
    ),
  },
  connections: {
    hook: useConnections,
    rowKey: (r) => `${r.nodeId}:${r.connectionId}`,
    filter: 'Remote address, client id or connection id',
    noun: 'connection',
    menu: {
      kind: 'connection',
      target: (r) => ({ nodeId: r.nodeId, nodeName: r.nodeName, connectionId: r.connectionId ?? '', snapshot: r }),
      label: (r) => r.clientId || r.remoteAddress || r.connectionId || 'connection',
    },
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
    action: (row, ctx) => (
      <CloseConnectionAction
        clusterId={ctx.clusterId}
        kind="connection"
        nodeId={row.nodeId}
        nodeName={row.nodeName}
        targetId={row.connectionId ?? ''}
        rowLabel={row.clientId || row.remoteAddress || row.connectionId || ''}
        fetchedAt={ctx.fetchedAt}
      />
    ),
  },
  producers: {
    hook: useProducers,
    rowKey: (r) => `${r.nodeId}:${r.producerId}`,
    filter: 'Address, producer name or session id',
    noun: 'producer',
    menu: {
      kind: 'producer',
      target: (r) => ({
        nodeId: r.nodeId,
        nodeName: r.nodeName,
        producerId: r.producerId ?? '',
        address: r.address,
        sessionId: r.sessionId,
        snapshot: r,
      }),
      label: (r) => r.name || r.address || r.producerId || 'producer',
    },
    columns: [
      {
        id: 'address',
        header: 'Address',
        accessor: (r) => r.address ?? '',
        cell: (r) => linked('address', r.address),
        sortKey: 'address',
      },
      { id: 'name', header: 'Name', accessor: (r) => r.name ?? '' },
      { id: 'protocol', header: 'Protocol', accessor: (r) => r.protocol ?? '', width: 100 },
      { id: 'sent', header: 'Sent', accessor: (r) => r.messagesSent, numeric: true, width: 100 },
      NODE_COL<ProducerView>(),
    ],
  },
};

/** The remaining five cross-node views, all from one column-spec-driven grid (ADR-0017). */
export function ResourceView({ kind }: { kind: Kind }) {
  // `/` focuses this view's filter (ADR-0109).
  const filterRef = useRef<HTMLInputElement>(null);
  useFilterShortcut(filterRef);
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

  const fetchedAt = query.dataUpdatedAt > 0 ? query.dataUpdatedAt : null;
  const columns = config.action
    ? [...config.columns, ACTION_COL(config.action, { clusterId, fetchedAt })]
    : config.columns;

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

  return (
    <Stack gap="sm">
      <Group justify="space-between">
        <TextInput
          ref={filterRef}
          label={`Filter ${kind}`}
          placeholder={config.filter}
          value={filter}
          onChange={(e) => setFilter(e.currentTarget.value)}
          w={280}
          size="xs"
        />
      </Group>

      {query.isPending && rows.length === 0 ? (
        <Stack gap={4}>
          {Array.from({ length: 12 }).map((_, i) => (
            <Skeleton key={i} height={30} />
          ))}
        </Stack>
      ) : (
        <VirtualTable
          label={kind.charAt(0).toUpperCase() + kind.slice(1)}
          columns={columns}
          data={rows}
          sort={search.sort}
          onSortChange={setSort}
          rowKey={config.rowKey}
          rowMenu={{
            label: config.menu.label,
            render: (row, menu) => (
              <ResourceActions
                kind={config.menu.kind}
                clusterId={clusterId}
                target={config.menu.target(row)}
                restoreFocus={menu.restoreFocus}
              />
            ),
          }}
          emptyLabel={
            <Text size="sm">
              No {config.noun}s right now. This view is a live read across every serving node — one
              request per node per load.
            </Text>
          }
        />
      )}

      <Pager
        page={page}
        pageSize={PAGE_SIZE}
        total={total}
        onChange={setPage}
        label={`${config.noun}s`}
      />
    </Stack>
  );
}
