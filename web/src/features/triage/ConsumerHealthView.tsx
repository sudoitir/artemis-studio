import { useEffect, useState } from 'react';
import { Alert, Group, Skeleton, Stack, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { useCluster } from '../clusters/index.ts';
import { useConsumerHealth, type ConsumerHealthView as HealthRow } from './api.ts';
import { HealthVerdict } from './HealthVerdict.tsx';
import { Pager } from '../../ui/Pager.tsx';
import { VirtualTable, type GridColumn } from '../../ui/VirtualTable.tsx';
import { formatRate, trendPhrase } from './verdict.ts';

const PAGE_SIZE = 200;

const columns: GridColumn<HealthRow>[] = [
  {
    id: 'verdict',
    header: 'Health',
    accessor: (r) => r.severity,
    cell: (r) => <HealthVerdict row={r} />,
    sortKey: 'severity',
    width: 150,
  },
  { id: 'queueName', header: 'Queue', accessor: (r) => r.queueName, sortKey: 'queueName' },
  { id: 'address', header: 'Address', accessor: (r) => r.address, sortKey: 'address' },
  {
    id: 'depth',
    header: 'Depth',
    accessor: (r) => r.depth,
    numeric: true,
    sortKey: 'depth',
    width: 110,
  },
  {
    id: 'consumers',
    header: 'Consumers',
    accessor: (r) => r.consumers,
    numeric: true,
    sortKey: 'consumers',
    width: 110,
  },
  {
    id: 'delivering',
    header: 'In flight',
    accessor: (r) => r.delivering,
    numeric: true,
    sortKey: 'delivering',
    width: 100,
  },
  {
    // An unmeasured rate reads "not measured", never 0 — on this screen those
    // two mean opposite things and lead to opposite actions.
    id: 'ackRate',
    header: 'Acknowledged',
    accessor: (r) => r.ackRate ?? -1,
    cell: (r) => formatRate(r.ackRate),
    numeric: true,
    width: 130,
  },
  {
    id: 'trend',
    header: 'Trend',
    accessor: (r) => r.depthSlopePerSecond ?? 0,
    cell: (r) => trendPhrase(r),
    width: 170,
  },
];

/**
 * Every queue in the cluster ranked by how badly it needs attention.
 *
 * This is the view the roadmap item exists for: the question during an incident
 * is "which of my queues have consumer trouble", and no existing screen answers
 * it — Metrics charts one queue at a time and Flow is bounded to the busiest
 * paths. Sorting, filtering and paging are URL-owned, so a triage view can be
 * pasted into a channel and reopened exactly as it was.
 */
export function ConsumerHealthView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as {
    q?: string;
    sort?: string;
    page?: number;
  };
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

  const query = useConsumerHealth(clusterId, {
    q: search.q,
    sort: search.sort,
    page,
    size: PAGE_SIZE,
  });

  // An empty grid has three different causes, and presenting an absence as a fact
  // is the one that misleads: a node Studio could not reach contributes no rows,
  // which looks exactly like a cluster whose queues are all healthy.
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
  const needingAttention = rows.filter((r) => r.severity >= 2).length;
  const unmeasured = rows.filter((r) => r.verdict === 'INSUFFICIENT_DATA').length;

  return (
    <Stack gap="sm">
      <Group justify="space-between">
        <TextInput
          placeholder="Filter by queue or address"
          value={filter}
          onChange={(e) => setFilter(e.currentTarget.value)}
          w={280}
          size="xs"
          aria-label="Filter by queue or address"
        />
        {/* Stated in words, and quiet when there is nothing to say. */}
        <Text size="xs" c="dimmed" role="status">
          {needingAttention > 0
            ? `${needingAttention} of ${rows.length} on this page need attention`
            : rows.length > 0
              ? 'Nothing on this page needs attention'
              : ''}
          {unmeasured > 0 ? ` · ${unmeasured} not yet measured` : ''}
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
          rowKey={(r) => `${r.address}::${r.queueName}`}
          emptyLabel={
            search.q ? (
              <Stack gap={4} align="flex-start">
                <Text fw={600}>No queue matches "{search.q}"</Text>
                <Text size="sm">
                  There may still be queues on this cluster — none of them match this filter.
                </Text>
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
                  last scrape, so this is an incomplete view rather than a healthy cluster.
                  {unreachable.length > 1 ? ` (${unreachable.join(', ')})` : ''}
                </Text>
              </Stack>
            ) : (
              <Stack gap={4} align="flex-start">
                <Text fw={600}>No queues to report on yet</Text>
                <Text size="sm">
                  Consumer health ranks every queue by whether its consumers are keeping up —
                  whether anything is attached, whether it is acknowledging, and whether the
                  backlog is growing. Queues appear here within a scrape tick of being created.
                </Text>
              </Stack>
            )
          }
        />
      )}

      <Pager page={page} pageSize={PAGE_SIZE} total={total} onChange={setPage} label="queues" />
    </Stack>
  );
}
