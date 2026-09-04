import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Anchor,
  Badge,
  Button,
  Group,
  Modal,
  Select,
  Skeleton,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { notifications } from '@mantine/notifications';

import {
  useCluster,
  useMessages,
  usePurgeQueue,
  type MessageSummaryView,
} from '../api/client.ts';
import { VirtualTable, type GridColumn } from '../grid/VirtualTable.tsx';
import { CapabilityLedger } from '../clusters/CapabilityLedger.tsx';
import { ConfirmByTyping } from '../shared/ConfirmByTyping.tsx';
import { MessageDetailPanel } from './MessageDetailPanel.tsx';
import { MessageActions } from './MessageActions.tsx';
import { SendMessage } from './SendMessage.tsx';

const PAGE_SIZE = 200;

function ts(ms: number): string {
  return ms > 0 ? new Date(ms).toISOString().replace('T', ' ').replace('.000Z', 'Z') : '—';
}

const columns: GridColumn<MessageSummaryView>[] = [
  { id: 'messageId', header: 'Message ID', accessor: (m) => m.messageId, sortKey: undefined, width: 150 },
  { id: 'timestamp', header: 'Enqueued', accessor: (m) => ts(m.timestamp), width: 200 },
  { id: 'priority', header: 'Prio', accessor: (m) => m.priority, numeric: true, width: 70 },
  {
    id: 'durable',
    header: 'Durable',
    accessor: (m) => m.durable,
    cell: (m) => (m.durable ? 'yes' : 'no'),
    width: 80,
  },
  { id: 'size', header: 'Size', accessor: (m) => m.size, numeric: true, width: 90 },
  { id: 'props', header: 'Props', accessor: (m) => m.propertyCount, numeric: true, width: 70 },
  {
    id: 'body',
    header: 'Body',
    accessor: (m) => m.bodyPreview ?? '',
    cell: (m) => (
      <Group gap={6} wrap="nowrap">
        <Text size="xs" truncate>
          {m.bodyPreview ?? <Text span c="dimmed">(empty)</Text>}
        </Text>
        {m.bodyTruncated ? (
          <Badge size="xs" color="yellow" variant="light">
            truncated
          </Badge>
        ) : null}
      </Group>
    ),
  },
];

/**
 * Browse one queue's messages (ADR-0021). Reached from a queue row, not a
 * top-level tab. Node, filter and page are URL-owned (non-negotiable #9);
 * message selection is ephemeral (added in a later slice). The whole view is
 * gated on the `messageIo` capability — when it is not available the ledger
 * explains why and shows the `broker.xml` snippet, with no missing controls
 * (non-negotiable #5).
 */
export function MessagesView() {
  const { clusterId, queueName } = useParams({ strict: false }) as {
    clusterId: string;
    queueName: string;
  };
  const search = useSearch({ strict: false }) as { node?: string; filter?: string; page?: number };
  const navigate = useNavigate();

  const cluster = useCluster(clusterId);
  const [filter, setFilter] = useState(search.filter ?? '');
  const [debounced] = useDebouncedValue(filter, 250);
  const [openId, setOpenId] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [sendOpen, setSendOpen] = useState(false);
  const [purgeOpen, setPurgeOpen] = useState(false);
  const page = search.page ?? 1;
  const purge = usePurgeQueue(clusterId, queueName);
  const [purgeCount, setPurgeCount] = useState<number | null>(null);

  // Selection is ephemeral (D10) — reset on any navigation of node / filter / page.
  useEffect(() => setSelected(new Set()), [search.node, search.filter, page]);

  const toggleRow = (key: string) =>
    setSelected((s) => {
      const next = new Set(s);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  const toggleAll = (keys: string[], allSelected: boolean) =>
    setSelected((s) => {
      const next = new Set(s);
      keys.forEach((k) => (allSelected ? next.delete(k) : next.add(k)));
      return next;
    });

  useEffect(() => {
    if ((search.filter ?? '') === debounced) return;
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({
        ...prev,
        filter: debounced || undefined,
        page: undefined,
      }),
    });
  }, [debounced, navigate, search.filter]);

  const endpoints = useMemo(
    () =>
      (cluster.data?.topology.nodes ?? [])
        .flatMap((n) => n.endpoints)
        .filter((e) => e.manageable),
    [cluster.data],
  );

  const messages = useMessages(clusterId, queueName, {
    node: search.node,
    filter: search.filter,
    page,
    size: PAGE_SIZE,
  });

  const messageIo = cluster.data?.capabilities.messageIo;
  const gated = messageIo && messageIo.status !== 'AVAILABLE';

  const backToQueues = { to: `/clusters/${clusterId}/queues` } as const;

  const header = (
    <Stack gap={4}>
      <Anchor component={Link} {...backToQueues} size="xs">
        ← All queues
      </Anchor>
      <Group justify="space-between" align="flex-end">
        <Title order={3}>{queueName}</Title>
        <Group gap="xs">
          {messages.data ? (
            <Text size="xs" c="dimmed">
              {messages.data.count} message{messages.data.count === 1 ? '' : 's'} · read from{' '}
              {endpoints.find((e) => e.id === messages.data.node)?.name ?? 'the live node'}
            </Text>
          ) : null}
          <Button size="xs" variant="light" onClick={() => setSendOpen(true)}>
            Send
          </Button>
          <Button
            size="xs"
            variant="light"
            color="red"
            onClick={() => {
              setPurgeCount(null);
              setPurgeOpen(true);
              purge.mutate(
                { node: search.node, dryRun: true },
                {
                  onSuccess: (r) => setPurgeCount('affectedCount' in r ? r.affectedCount : null),
                  onError: (e) => notifications.show({ color: 'red', message: e.message }),
                },
              );
            }}
          >
            Purge queue
          </Button>
        </Group>
      </Group>
    </Stack>
  );

  if (cluster.data && gated) {
    return (
      <Stack gap="md">
        {header}
        <Alert color="yellow" variant="light" title="Message operations are not available here">
          This connection cannot browse messages. The reason and the exact{' '}
          <code>broker.xml</code> change are below.
        </Alert>
        <CapabilityLedger capabilities={cluster.data.capabilities} />
      </Stack>
    );
  }

  if (messages.isError) {
    return (
      <Stack gap="md">
        {header}
        <Alert color="red" variant="light" title={messages.error.title}>
          {messages.error.message}
        </Alert>
      </Stack>
    );
  }

  const rows = messages.data?.data ?? [];
  const total = messages.data?.count ?? 0;
  const lastPage = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const setNode = (node: string | null) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, node: node || undefined, page: undefined }) });

  return (
    <Stack gap="sm">
      {header}

      <Group justify="space-between">
        <Group gap="xs">
          <TextInput
            placeholder="Selector, e.g. region = 'eu'"
            value={filter}
            onChange={(e) => setFilter(e.currentTarget.value)}
            w={280}
            size="xs"
          />
          {endpoints.length > 1 ? (
            <Select
              size="xs"
              w={180}
              placeholder="Live node"
              clearable
              value={search.node ?? null}
              onChange={setNode}
              data={endpoints.map((e) => ({ value: e.id, label: e.name }))}
              aria-label="Node to browse"
            />
          ) : null}
        </Group>
        <Text size="xs" c="dimmed">
          page {page} of {lastPage}
        </Text>
      </Group>

      <MessageActions
        clusterId={clusterId}
        queueName={queueName}
        node={search.node}
        selected={selected}
        onCleared={() => setSelected(new Set())}
      />

      {messages.isPending && rows.length === 0 ? (
        <Stack gap={4}>
          {Array.from({ length: 12 }).map((_, i) => (
            <Skeleton key={i} height={30} />
          ))}
        </Stack>
      ) : (
        <VirtualTable
          columns={columns}
          data={rows}
          rowKey={(m) => String(m.messageId)}
          onRowClick={(m) => setOpenId(String(m.messageId))}
          selectable
          selected={selected}
          onToggleRow={toggleRow}
          onToggleAll={toggleAll}
          emptyLabel={
            <Stack gap={4}>
              <Text fw={600}>No messages match</Text>
              <Text size="sm">
                This queue is empty, or your selector excluded every message. Messages here are read
                over Jolokia as text — faithful binary bodies arrive with the Core client in Phase 4.
              </Text>
            </Stack>
          }
        />
      )}

      <MessageDetailPanel
        clusterId={clusterId}
        queueName={queueName}
        messageId={openId}
        node={search.node}
        filter={search.filter}
        onClose={() => setOpenId(null)}
      />

      <SendMessage
        clusterId={clusterId}
        queueName={queueName}
        node={search.node}
        opened={sendOpen}
        onClose={() => setSendOpen(false)}
      />

      <Modal opened={purgeOpen} onClose={() => setPurgeOpen(false)} title={`Purge ${queueName}?`}>
        <Stack gap="sm">
          <Text size="sm">
            {purgeCount === null
              ? 'Estimating current depth…'
              : `This will remove approximately ${purgeCount} message${purgeCount === 1 ? '' : 's'} (point-in-time estimate). This cannot be undone.`}
          </Text>
          <ConfirmByTyping
            token={queueName}
            confirmLabel="Purge queue"
            loading={purge.isPending}
            disabled={purgeCount === null}
            onConfirm={() =>
              purge.mutate(
                { node: search.node, override: true },
                {
                  onSuccess: (r) => {
                    notifications.show({
                      message: `Purged ${'affectedCount' in r ? r.affectedCount : ''} messages`,
                    });
                    setPurgeOpen(false);
                  },
                  onError: (e) => notifications.show({ color: 'red', message: e.message }),
                },
              )
            }
          />
        </Stack>
      </Modal>
    </Stack>
  );
}
