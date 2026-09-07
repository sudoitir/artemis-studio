import { useCallback, useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Code,
  Drawer,
  Group,
  Select,
  Skeleton,
  Stack,
  Switch,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { useCluster, useEvents, type BrokerEventView } from '../api/client.ts';
import { useClusterStream } from '../api/stream.ts';
import { VirtualTable, type GridColumn } from '../grid/VirtualTable.tsx';
import { Pager } from '../grid/Pager.tsx';
import styles from './EventsView.module.css';
import { absoluteLabel } from '../app/time.ts';
import { useDisplayZone } from '../app/timezone.ts';

const LIVE_BUFFER_MAX = 500;

const PAGE_SIZE = 100;

/** Notification classes seen against the dev pair (broker-management-notes §7). */
const TYPES = [
  'BINDING_ADDED',
  'BINDING_REMOVED',
  'CONSUMER_CREATED',
  'CONSUMER_CLOSED',
  'CONNECTION_CREATED',
  'CONNECTION_DESTROYED',
  'SESSION_CREATED',
  'SESSION_CLOSED',
  'ADDRESS_ADDED',
  'ADDRESS_REMOVED',
  'MESSAGE_DELIVERED',
  'MESSAGE_EXPIRED',
];

/** A family word + tone so colour is never the only signal (non-negotiable #6). */
function family(type: string): { word: string; color: string } {
  if (type.startsWith('CONSUMER')) return { word: 'consumer', color: 'blue' };
  if (type.startsWith('SESSION')) return { word: 'session', color: 'grape' };
  if (type.startsWith('CONNECTION')) return { word: 'connection', color: 'indigo' };
  if (type.startsWith('BINDING') || type.startsWith('ADDRESS'))
    return { word: 'binding', color: 'teal' };
  if (type.startsWith('MESSAGE')) return { word: 'message', color: 'orange' };
  if (type.startsWith('UNKNOWN')) return { word: 'unknown', color: 'gray' };
  return { word: 'other', color: 'gray' };
}

/** UTC, second precision — a broker event's useful comparison is to another one. */
function occurredAt(e: BrokerEventView): string {
  return absoluteLabel(e.occurredAt);
}

function subjectOf(e: BrokerEventView): string {
  return e.consumerName ?? e.sessionName ?? e.connectionName ?? e.routingName ?? '—';
}

/**
 * The props payload used to expand inline under its row. A virtualised grid has
 * no row to expand under — and a drawer is the better home anyway: the JSON is
 * frequently taller than the viewport, which an inline `Collapse` handled by
 * pushing every row below it off the screen.
 */
const columns: GridColumn<BrokerEventView>[] = [
  { id: 'time', header: 'Time', accessor: occurredAt, width: 200 },
  {
    id: 'type',
    header: 'Type',
    accessor: (e) => e.type,
    width: 280,
    cell: (e) => {
      const fam = family(e.type);
      return (
        <Group gap={6} wrap="nowrap">
          <Badge size="xs" variant="light" color={fam.color}>
            {fam.word}
          </Badge>
          <Text size="xs" ff="monospace">
            {e.type}
          </Text>
        </Group>
      );
    },
  },
  { id: 'address', header: 'Address', accessor: (e) => e.address ?? '—' },
  { id: 'subject', header: 'Subject', accessor: subjectOf },
  { id: 'remote', header: 'Remote', accessor: (e) => e.remoteAddress ?? '—', width: 180 },
];

/** The events screen: this cluster's activemq.notifications history, newest first. */
export function EventsView() {
  // Absolute timestamps here read the display zone from module state, so this
  // subscribes the view to a zone change (`app/timezone.ts`).
  useDisplayZone();
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as {
    type?: string;
    address?: string;
    page?: number;
  };
  const navigate = useNavigate();

  const cluster = useCluster(clusterId);
  const notifications = cluster.data?.capabilities.notifications;

  const [address, setAddress] = useState(search.address ?? '');
  const [debouncedAddress] = useDebouncedValue(address, 250);
  const page = search.page ?? 1;

  const [selected, setSelected] = useState<BrokerEventView | null>(null);
  const [live, setLive] = useState(true);
  const [buffer, setBuffer] = useState<BrokerEventView[]>([]);
  const onEvent = useCallback((e: BrokerEventView) => {
    setBuffer((prev) =>
      prev.some((x) => x.seq === e.seq) ? prev : [e, ...prev].slice(0, LIVE_BUFFER_MAX),
    );
  }, []);
  useClusterStream(clusterId, live ? ['events'] : [], onEvent);

  const setParam = (patch: Record<string, unknown>) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, ...patch, page: undefined }),
    });

  const query = useEvents(clusterId, {
    type: search.type,
    address: debouncedAddress || undefined,
    page,
    size: PAGE_SIZE,
  });

  const total = query.data?.count ?? 0;
  const dropped = query.data?.dropped ?? 0;

  // On page 1 with no filter, merge the live buffer over the fetched history,
  // newest first, de-duplicated on seq.
  const historyData = query.data?.data;
  const rows = useMemo(() => {
    const history = historyData ?? [];
    const showLive = page === 1 && !search.type && !debouncedAddress;
    if (!showLive || buffer.length === 0) return history;
    const seen = new Set(buffer.map((e) => e.seq));
    return [...buffer, ...history.filter((e) => !seen.has(e.seq))];
  }, [buffer, historyData, page, search.type, debouncedAddress]);

  // Notifications not available: name the gap, show the broker.xml, infer nothing
  // (same stance as DlqView on address settings). All hooks run above this.
  if (notifications && notifications.status !== 'AVAILABLE') {
    return (
      <Stack gap="sm">
        <Title order={3}>Events</Title>
        <Alert
          color={notifications.status === 'UNKNOWN' ? 'blue' : 'yellow'}
          variant="light"
          title="Live events not available"
        >
          {notifications.reason}
        </Alert>
        {notifications.brokerXmlSnippet ? (
          <CodeHighlight code={notifications.brokerXmlSnippet} language="xml" />
        ) : null}
      </Stack>
    );
  }

  return (
    <Stack gap="sm">
      <Group justify="space-between" align="flex-end">
        <Title order={3}>Events</Title>
        <Group gap="md">
          <Switch
            size="xs"
            label="Live"
            checked={live}
            onChange={(e) => setLive(e.currentTarget.checked)}
          />
        </Group>
      </Group>

      {dropped > 0 ? (
        <Alert color="orange" variant="light" title="Some events were dropped">
          {dropped} notification{dropped === 1 ? ' has' : 's have'} been dropped for this cluster
          because they arrived faster than the write buffer could be flushed. Raise{' '}
          <code>events.buffer-size</code> in settings if this persists.
        </Alert>
      ) : null}

      <Group gap="xs">
        <Select
          placeholder="Any type"
          size="xs"
          w={220}
          clearable
          searchable
          value={search.type ?? null}
          onChange={(v) => setParam({ type: v || undefined })}
          data={TYPES}
        />
        <TextInput
          placeholder="Filter by address"
          value={address}
          onChange={(e) => setAddress(e.currentTarget.value)}
          onBlur={() => setParam({ address: debouncedAddress || undefined })}
          size="xs"
          w={220}
        />
      </Group>

      {query.isError ? (
        <Alert color="red" variant="light" title={query.error.title}>
          {query.error.message}
        </Alert>
      ) : query.isPending && rows.length === 0 ? (
        <Stack gap={4}>
          {Array.from({ length: 12 }).map((_, i) => (
            <Skeleton key={i} height={28} />
          ))}
        </Stack>
      ) : rows.length === 0 ? (
        <Text size="sm" c="dimmed">
          No broker events recorded yet. Consumer, session, connection and binding activity on this
          cluster's brokers shows up here as it happens.
        </Text>
      ) : (
        <VirtualTable
          columns={columns}
          data={rows}
          rowKey={(e) => String(e.seq)}
          onRowClick={setSelected}
        />
      )}

      <Pager
        page={page}
        pageSize={PAGE_SIZE}
        total={total}
        onChange={(next) =>
          navigate({
            to: '.',
            search: (p: Record<string, unknown>) => ({ ...p, page: next > 1 ? next : undefined }),
          })
        }
        label="events"
      />

      <Drawer
        opened={selected !== null}
        onClose={() => setSelected(null)}
        position="right"
        size="lg"
        title={selected ? selected.type : ''}
      >
        {selected ? (
          <Stack gap="xs">
            <Text size="xs" c="dimmed">
              {occurredAt(selected)} · {selected.address ?? 'no address'} ·{' '}
              {subjectOf(selected)}
            </Text>
            {selected.props && Object.keys(selected.props).length > 0 ? (
              <Code block className={styles.props}>
                {JSON.stringify(selected.props, null, 2)}
              </Code>
            ) : (
              <Text size="sm" c="dimmed">
                This notification carried no properties.
              </Text>
            )}
          </Stack>
        ) : null}
      </Drawer>

    </Stack>
  );
}
