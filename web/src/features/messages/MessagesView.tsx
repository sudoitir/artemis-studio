import { useEffect, useMemo, useState, useRef } from 'react';
import { Alert, Anchor, Button, Group, Modal, Select, Stack, Text, TextInput, Title } from '@mantine/core';
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { notifications } from '@mantine/notifications';

import { CapabilityLedger, useCluster } from '../clusters/index.ts';
import { useMessages, usePurgeQueue, type DryRunView, type MessageSummaryView } from './api.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { messageColumns } from './columns.ts';
import { MessageDetailPanel } from './MessageDetailPanel.tsx';
import { MessageActions } from './MessageActions.tsx';
import { SendMessage } from './SendMessage.tsx';
import { useCan } from '../../kernel/auth/useCan.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { useSlot, type MessageSelection } from '../../kernel/slots.ts';
import { ResourceActions } from '../../kernel/actions/ResourceActions.tsx';
import { useTitlePart } from '../../kernel/shell/pageTitle.ts';
import { useFilterShortcut } from '../../kernel/keyboard/filterShortcut.ts';

const PAGE_SIZE = 200;

const NO_ROWS: MessageSummaryView[] = [];

const messageKey = (m: MessageSummaryView) => String(m.messageId);

/**
 * Whether the caller may take a message operation here, and why not when they may not. Offered while
 * grants and the cluster load, and blocked only on a known refusal (non-negotiable #5).
 */
function useMessageGate(clusterId: string, permission: string, label: string): GateVerdict {
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  return gateFor(can(permission, clusterId), label, cluster.data?.capabilities.messageIo, loading || cluster.isPending);
}

/** "12 messages", or why the total is not known. */
function countLabel(page: { count?: number | null; countUnavailable?: string | null }): string {
  if (page.count == null) return `total unavailable — ${page.countUnavailable ?? 'the broker did not report it'}`;
  return `${page.count} message${page.count === 1 ? '' : 's'}`;
}

/** Picked rows win; otherwise the selector in force, otherwise the whole queue. */
function selectionOf(pickedIds: number[], filter: string | undefined): MessageSelection {
  if (pickedIds.length > 0) return { kind: 'ids', ids: pickedIds };
  return filter ? { kind: 'filter', filter } : { kind: 'all' };
}

function selectionTotalOf(selection: MessageSelection, total: number | null | undefined): number | null {
  if (selection.kind === 'ids') return selection.ids.length;
  return selection.kind === 'all' ? (total ?? null) : null;
}

function estimateSentence(affected: number): string {
  return `This will remove approximately ${affected} message${affected === 1 ? '' : 's'} (point-in-time estimate). This cannot be undone.`;
}

/** Purge the whole queue: estimate first, then confirm by typing the queue's name. */
function PurgeQueue({ clusterId, queueName, node }: Readonly<{ clusterId: string; queueName: string; node?: string }>) {
  const [purgeOpen, setPurgeOpen] = useState(false);
  const purge = usePurgeQueue(clusterId, queueName);
  // The whole preview, not just its count: the cap and whether the estimate is over
  // it decide both what the dialog says and whether the purge may override it.
  const [purgePreview, setPurgePreview] = useState<DryRunView | null>(null);
  const [purgeFailed, setPurgeFailed] = useState<string | null>(null);
  const purgeOverCap = purgePreview?.overCap ?? false;
  const gate = useMessageGate(clusterId, 'queue:purge', 'Purge queues');

  return (
    <>
      <CapabilityGate verdict={gate} what="purging this queue">
        <Button
          size="xs"
          variant="light"
          color="red"
          disabled={gate.kind === 'blocked'}
          onClick={() => {
            setPurgePreview(null);
            setPurgeFailed(null);
            setPurgeOpen(true);
            purge.mutate(
              { node, dryRun: true },
              {
                onSuccess: (r) => setPurgePreview('cap' in r ? r : null),
                onError: (e) => setPurgeFailed(e.message),
              },
            );
          }}
        >
          Purge queue
        </Button>
      </CapabilityGate>
      <Modal opened={purgeOpen} onClose={() => setPurgeOpen(false)} title={`Purge ${queueName}?`}>
        <Stack gap="sm">
          {/* An unavailable estimate is stated, never omitted: an absent number reads
              as zero, and a confirmation disabled with no reason reads as a bug. */}
          {purgeFailed ? (
            <Alert color="yellow" variant="light" title="The estimate could not be taken" role="alert">
              {purgeFailed} The purge can still proceed, but Studio cannot tell you how many messages it would destroy.
              This cannot be undone. The broker's bulk safety cap still applies: if the depth turns out to be over it,
              the purge is refused.
            </Alert>
          ) : (
            <Text size="sm">
              {purgePreview === null ? 'Estimating current depth…' : estimateSentence(purgePreview.affectedCount)}
            </Text>
          )}
          {/* The cap is overridden only where the operator was told the number it
              is being overridden for — never on an unknown depth. */}
          {purgeOverCap && purgePreview ? (
            <Alert color="yellow" variant="light" title="Over the safety cap">
              This would remove {purgePreview.affectedCount.toLocaleString()} messages, over the cap of{' '}
              {purgePreview.cap.toLocaleString()}. Confirming will override the cap for this operation, and the override
              is recorded in the audit log.
            </Alert>
          ) : null}
          <ConfirmByTyping
            token={queueName}
            confirmLabel={purgeOverCap ? 'Purge anyway, over the cap' : 'Purge queue'}
            loading={purge.isPending}
            disabled={purgePreview === null && purgeFailed === null}
            onConfirm={() =>
              purge.mutate(
                { node, override: purgeOverCap },
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
    </>
  );
}

/** Why the grid is empty: the selector, nodes that did not answer, or genuinely nothing in the queue. */
function MessagesEmpty({
  filtered,
  unreachable,
  onClearSelector,
}: Readonly<{ filtered: boolean; unreachable: string[]; onClearSelector: () => void }>) {
  if (filtered) {
    return (
      <EmptyState
        kind="filtered"
        title="No message matches this selector"
        description="The selector excludes every message in this queue. Clear it to browse them all."
        onClearFilters={onClearSelector}
      />
    );
  }
  if (unreachable.length > 0) {
    return <EmptyState kind="unreachable" title="No messages could be listed" nodes={unreachable} />;
  }
  return (
    <EmptyState
      kind="empty"
      title="This queue has no messages"
      description="Messages wait in a queue until a consumer takes them. Produce to the queue, or use Send above to add one. Messages here are read over Jolokia as text — faithful binary bodies need the Core client."
    />
  );
}

/**
 * Browse one queue's messages (ADR-0021). Reached from a queue row, not a
 * top-level tab. Node, filter and page are URL-owned (non-negotiable #9);
 * message selection is ephemeral (added in a later slice). The whole view is
 * gated on the `messageIo` capability — when it is not available the ledger
 * explains why and shows the `broker.xml` snippet, with no missing controls
 * (non-negotiable #5).
 */
export function MessagesView() {
  // `/` focuses this view's filter (ADR-0109).
  const filterRef = useRef<HTMLInputElement>(null);
  useFilterShortcut(filterRef);
  // Absolute timestamps here read the display zone from module state, so this
  // subscribes the view to a zone change (`app/timezone.ts`).
  const zone = useDisplayZone();
  const { clusterId, queueName } = useParams({ strict: false }) as {
    clusterId: string;
    queueName: string;
  };
  const search = useSearch({ strict: false }) as { node?: string; filter?: string; page?: number; message?: string };
  const navigate = useNavigate();

  const cluster = useCluster(clusterId);
  const sendGate = useMessageGate(clusterId, 'message:send', 'Send messages');
  const columns = useMemo(() => messageColumns(zone), [zone]);
  const [filter, setFilter] = useState(search.filter ?? '');
  const [debounced] = useDebouncedValue(filter, 250);
  // The open message is in the address, so a link to one message opens it (non-negotiable #9).
  const openId = search.message ?? null;
  useTitlePart('resource', openId ? `${queueName} › message ${openId}` : queueName);
  const setOpenId = (id: string | null) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, message: id ?? undefined }) });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [sendOpen, setSendOpen] = useState(false);
  const page = search.page ?? 1;
  const selectionSlot = useSlot('messages.selection');

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
    void navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({
        ...prev,
        filter: debounced || undefined,
        page: undefined,
      }),
    });
  }, [debounced, navigate, search.filter]);

  const endpoints = useMemo(
    () => (cluster.data?.topology.nodes ?? []).flatMap((n) => n.endpoints).filter((e) => e.manageable),
    [cluster.data],
  );

  const messages = useMessages(clusterId, queueName, {
    node: search.node,
    filter: search.filter,
    page,
    size: PAGE_SIZE,
  });

  const messageIo = cluster.data?.capabilities.messageIo;
  // Block only on a known refusal. Since ADR-0049 D5 this capability is UNKNOWN
  // until a management write has actually been attempted, and blocking on absence
  // of evidence would lock every operator out of a working broker until something
  // else happened to write to it first.
  const gated = messageIo?.status === 'UNAVAILABLE';
  const unproven = messageIo?.status === 'UNKNOWN';

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
              {countLabel(messages.data)} · read from{' '}
              {endpoints.find((e) => e.id === messages.data.node)?.name ?? 'the live node'}
            </Text>
          ) : null}
          <CapabilityGate verdict={sendGate} what="sending a message">
            <Button size="xs" variant="light" disabled={sendGate.kind === 'blocked'} onClick={() => setSendOpen(true)}>
              Send
            </Button>
          </CapabilityGate>
          <PurgeQueue clusterId={clusterId} queueName={queueName} node={search.node} />
        </Group>
      </Group>
    </Stack>
  );

  const uncertainty = unproven ? (
    <Alert color="gray" variant="light" title="Not yet established for this connection">
      No management write has been attempted here yet, so Studio cannot say for certain that message operations will
      work. They are offered anyway — the first one settles it.
    </Alert>
  ) : null;

  if (cluster.data && gated) {
    return (
      <Stack gap="md">
        {header}
        <Alert color="yellow" variant="light" title="Message operations are not available here">
          This connection cannot browse messages. The reason and the exact <code>broker.xml</code> change are below.
        </Alert>
        <CapabilityLedger capabilities={cluster.data.capabilities} clusterId={clusterId} />
      </Stack>
    );
  }

  const rows = messages.data?.data ?? NO_ROWS;
  // An unavailable total is stated, never read as zero: the page count is then unknown too.
  const total = messages.data?.count;
  const lastPage = total == null ? null : Math.max(1, Math.ceil(total / PAGE_SIZE));

  // Picked rows win; otherwise the selector in force, otherwise the whole queue.
  const pickedIds = [...selected].map(Number).filter((n) => Number.isFinite(n));
  const selection = selectionOf(pickedIds, search.filter);
  // A selector's match count is the preview's to establish; the page's total is the whole queue.
  const selectionTotal = selectionTotalOf(selection, total);

  // A node Studio could not reach contributes no rows, which looks exactly like a queue with none.
  const unreachable = (cluster.data?.topology.nodes ?? [])
    .flatMap((n) => n.endpoints)
    .filter((e) => e.lastError)
    .map((e) => e.name);
  // The address follows the field once typing pauses, so clearing the field clears the selector.
  const clearSelector = () => setFilter('');

  const setNode = (node: string | null) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, node: node || undefined, page: undefined }),
    });

  return (
    <Stack gap="sm">
      {header}
      {uncertainty}

      <Group justify="space-between">
        <Group gap="xs">
          <TextInput
            ref={filterRef}
            label="Message selector"
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
        <Group gap="xs">
          <Text size="xs" c="dimmed">
            {lastPage == null ? `page ${page} · total unavailable` : `page ${page} of ${lastPage}`}
          </Text>
          {selectionSlot.map(({ id, Component }) => (
            <Component
              key={id}
              clusterId={clusterId}
              queueName={queueName}
              node={search.node}
              selection={selection}
              total={selectionTotal}
              clear={() => setSelected(new Set())}
            />
          ))}
        </Group>
      </Group>

      <MessageActions
        clusterId={clusterId}
        queueName={queueName}
        node={search.node}
        selected={selected}
        onCleared={() => setSelected(new Set())}
      />

      <DataTable
        label="Messages"
        storageKey="messages"
        height="fill"
        columns={columns}
        data={rows}
        rowKey={messageKey}
        loading={messages.isPending}
        error={
          messages.isError ? <ErrorState error={messages.error} onRetry={() => void messages.refetch()} /> : undefined
        }
        onRowClick={(m) => setOpenId(String(m.messageId))}
        selectable
        selected={selected}
        onToggleRow={toggleRow}
        onToggleAll={toggleAll}
        rowMenu={{
          label: (m) => `message ${m.messageId}`,
          render: (m, menu) => (
            <ResourceActions
              kind="message"
              clusterId={clusterId}
              target={{ queueName, messageId: m.messageId, node: search.node }}
              restoreFocus={menu.restoreFocus}
            />
          ),
        }}
        empty={
          <MessagesEmpty filtered={Boolean(search.filter)} unreachable={unreachable} onClearSelector={clearSelector} />
        }
      />

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
    </Stack>
  );
}
