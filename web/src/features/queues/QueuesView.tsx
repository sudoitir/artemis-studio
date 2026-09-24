import { useEffect, useState } from 'react';
import { Alert, Button, Group, Skeleton, Stack, Text, TextInput } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { useCluster } from '../clusters/index.ts';
import { useQueue, useQueues, type QueueView } from './api.ts';
import { VirtualTable, type GridColumn } from '../../ui/VirtualTable.tsx';
import { Pager } from '../../ui/Pager.tsx';
import { QueueDetailDrawer } from './QueueDetailDrawer.tsx';
import { CreateQueueForm } from './CreateQueueForm.tsx';
import { useCan } from '../../kernel/auth/useCan.ts';
import { useSlot, type QueueSelection } from '../../kernel/slots.ts';
import { ResourceActions } from '../../kernel/actions/ResourceActions.tsx';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';

const PAGE_SIZE = 200;

const rowKey = (r: QueueView) => `${r.address}::${r.queueName}::${r.routingType}`;

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
  const search = useSearch({ strict: false }) as { q?: string; sort?: string; page?: number; queue?: string };
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

  // The open queue is an address, not a copy: held as a name and looked up in the
  // rows each render, so a pause, an edit or a delete that refetches the listing is
  // reflected in the panel instead of leaving it showing the queue as it was when
  // it was opened (non-negotiable #9).
  //
  // A shared or palette link can name a queue that is not on the loaded page; it is looked up by
  // name then, rather than the link silently opening nothing.
  const onPage = (query.data?.data ?? []).find((q) => q.queueName === search.queue);
  const offPage = useQueue(clusterId, onPage ? undefined : search.queue);
  const selected = onPage ?? offPage.queue ?? null;
  const setSelected = (queue: QueueView | null) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, queue: queue?.queueName }) });
  const [createOpen, setCreateOpen] = useState(false);
  const { can, loading: grantsLoading } = useCan();
  const mayCreate = can('queue:create', clusterId);

  // An empty grid has three quite different causes, and presenting an absence as
  // a fact is the one that misleads: a node Studio could not reach contributes no
  // rows, which looks exactly like a cluster with no queues.
  const cluster = useCluster(clusterId);
  // Offered while grants load, and never hidden: an operator without the grant sees why (non-negotiable #5).
  const createGate = gateFor(
    mayCreate,
    'Create queues and addresses',
    cluster.data?.capabilities.managementWrite,
    grantsLoading || cluster.isPending,
  );
  const unreachable = (cluster.data?.topology.nodes ?? [])
    .flatMap((n) => n.endpoints)
    .filter((e) => e.lastError)
    .map((e) => e.name);

  // Selection is local and belongs to the filter it was made under: a new filter is a new set of
  // queues, and carrying picks across it would act on queues the operator can no longer see.
  // Picks are keyed by row, holding the queue name the bulk actions need.
  const [picked, setPicked] = useState<Map<string, string>>(new Map());
  const [allMatching, setAllMatching] = useState(false);
  const clearSelection = () => {
    setPicked(new Map());
    setAllMatching(false);
  };
  useEffect(() => {
    setPicked(new Map());
    setAllMatching(false);
  }, [search.q]);
  const selectionSlot = useSlot('queues.selection');

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

  // "All matching" is a filter, not a list of names: the queues on the other pages were never loaded.
  // Changing any one row turns it back into names, starting from this page.
  const pageNames = () => new Map(rows.map((r) => [rowKey(r), r.queueName]));
  const toggleRow = (key: string) => {
    const next = allMatching ? pageNames() : new Map(picked);
    setAllMatching(false);
    if (next.has(key)) next.delete(key);
    else next.set(key, rows.find((r) => rowKey(r) === key)?.queueName ?? key);
    setPicked(next);
  };
  const toggleAll = (keys: string[], allSelected: boolean) => {
    const next = allMatching ? pageNames() : new Map(picked);
    setAllMatching(false);
    if (allSelected) keys.forEach((k) => next.delete(k));
    else rows.forEach((r) => next.set(rowKey(r), r.queueName));
    setPicked(next);
  };
  const selectedKeys: ReadonlySet<string> = allMatching ? new Set(rows.map(rowKey)) : new Set(picked.keys());
  const count = allMatching ? total : picked.size;
  const pageAllPicked = rows.length > 0 && rows.every((r) => picked.has(rowKey(r)));
  const matching = search.q ? ` matching "${search.q}"` : ' on this cluster';
  const selection: QueueSelection = allMatching
    ? { kind: 'filter', q: search.q ?? '', total }
    : { kind: 'names', names: [...picked.values()] };

  return (
    <Stack gap="sm">
      <Group justify="space-between" align="flex-end">
        <TextInput
          label="Filter queues"
          placeholder="Queue or address name"
          value={filter}
          onChange={(e) => setFilter(e.currentTarget.value)}
          w={280}
          size="xs"
        />
        <Group gap="xs" align="flex-end">
          {/* The count and position live in the pager, stated once. */}
          <CapabilityGate verdict={createGate} what="creating a queue">
            <Button size="xs" disabled={createGate.kind === 'blocked'} onClick={() => setCreateOpen(true)}>
              New queue
            </Button>
          </CapabilityGate>
        </Group>
      </Group>

      {/* Always mounted, so the first tick does not push the grid down under the cursor. */}
      <Group
        gap="sm"
        justify="space-between"
        role="region"
        aria-label="Selected queues"
        style={{ position: 'sticky', insetBlockStart: 0, zIndex: 2, background: 'var(--as-surface)' }}
      >
        <Group gap="xs">
          <Text size="sm" fw={count > 0 ? 600 : undefined}>
            {count === 0
              ? 'No queues selected. Select queues in the grid to act on them together.'
              : allMatching
                ? `All ${total.toLocaleString()} queues${matching} are selected.`
                : `${count.toLocaleString()} ${count === 1 ? 'queue' : 'queues'} selected`}
          </Text>
          {!allMatching && pageAllPicked && total > rows.length ? (
            <Button size="xs" variant="subtle" onClick={() => setAllMatching(true)}>
              {`Select all ${total.toLocaleString()} queues${matching}`}
            </Button>
          ) : null}
          {count > 0 ? (
            <Button size="xs" variant="subtle" onClick={clearSelection}>
              Clear selection
            </Button>
          ) : null}
        </Group>
        <Group gap="xs">
          {selectionSlot.map(({ id, Component }) => (
            <Component key={id} clusterId={clusterId} selection={selection} count={count} clear={clearSelection} />
          ))}
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
          label="Queues"
          columns={columns}
          data={rows}
          sort={search.sort}
          onSortChange={setSort}
          onRowClick={setSelected}
          rowKey={rowKey}
          selectable
          selected={selectedKeys}
          onToggleRow={toggleRow}
          onToggleAll={toggleAll}
          rowMenu={{
            label: (r) => r.queueName,
            render: (r, menu) => (
              <ResourceActions
                kind="queue"
                clusterId={clusterId}
                target={{ queueName: r.queueName, address: r.address, snapshot: r }}
                restoreFocus={menu.restoreFocus}
              />
            ),
          }}
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
