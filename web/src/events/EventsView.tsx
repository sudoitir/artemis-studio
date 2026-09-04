import { useCallback, useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Code,
  Collapse,
  Group,
  Select,
  Skeleton,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { useCluster, useEvents, type BrokerEventView } from '../api/client.ts';
import { useClusterStream } from '../api/stream.ts';
import styles from './EventsView.module.css';

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

function Row({ e }: { e: BrokerEventView }) {
  const [open, setOpen] = useState(false);
  const fam = family(e.type);
  const hasProps = e.props && Object.keys(e.props).length > 0;
  return (
    <>
      <Table.Tr
        onClick={hasProps ? () => setOpen((v) => !v) : undefined}
        style={{ cursor: hasProps ? 'pointer' : undefined }}
      >
        <Table.Td>
          <Text size="xs">
            {new Date(e.occurredAt).toISOString().replace('T', ' ').replace('.000Z', 'Z')}
          </Text>
        </Table.Td>
        <Table.Td>
          <Group gap={6} wrap="nowrap">
            <Badge size="xs" variant="light" color={fam.color}>
              {fam.word}
            </Badge>
            <Text size="xs" ff="monospace">
              {e.type}
            </Text>
          </Group>
        </Table.Td>
        <Table.Td>
          <Text size="xs">{e.address ?? '—'}</Text>
        </Table.Td>
        <Table.Td>
          <Text size="xs">
            {e.consumerName ?? e.sessionName ?? e.connectionName ?? e.routingName ?? '—'}
          </Text>
        </Table.Td>
        <Table.Td>
          <Text size="xs">{e.remoteAddress ?? '—'}</Text>
        </Table.Td>
      </Table.Tr>
      {hasProps ? (
        <Table.Tr>
          <Table.Td colSpan={5} p={0}>
            <Collapse expanded={open}>
              <Code block className={styles.props}>
                {JSON.stringify(e.props, null, 2)}
              </Code>
            </Collapse>
          </Table.Td>
        </Table.Tr>
      ) : null}
    </>
  );
}

/** The events screen: this cluster's activemq.notifications history, newest first. */
export function EventsView() {
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
  const lastPage = Math.max(1, Math.ceil(total / PAGE_SIZE));

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
          <Text size="xs" c="dimmed">
            {total} event{total === 1 ? '' : 's'} · page {page} of {lastPage}
          </Text>
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
        <Table.ScrollContainer minWidth={720} type="native">
          <Table stickyHeader highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Time</Table.Th>
                <Table.Th>Type</Table.Th>
                <Table.Th>Address</Table.Th>
                <Table.Th>Subject</Table.Th>
                <Table.Th>Remote</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((e) => (
                <Row key={e.seq} e={e} />
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}

      {lastPage > 1 ? (
        <Group justify="flex-end" gap="xs">
          <Button
            size="xs"
            variant="default"
            disabled={page <= 1}
            onClick={() =>
              navigate({
                to: '.',
                search: (p: Record<string, unknown>) => ({
                  ...p,
                  page: page - 1 > 1 ? page - 1 : undefined,
                }),
              })
            }
          >
            Previous
          </Button>
          <Button
            size="xs"
            variant="default"
            disabled={page >= lastPage}
            onClick={() =>
              navigate({
                to: '.',
                search: (p: Record<string, unknown>) => ({ ...p, page: page + 1 }),
              })
            }
          >
            Next
          </Button>
        </Group>
      ) : null}
    </Stack>
  );
}
