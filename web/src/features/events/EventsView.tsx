import { useCallback, useMemo, useState, useRef } from 'react';
import { Alert, Code, Drawer, Group, Select, Skeleton, Stack, Switch, Text, TextInput, Title } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';
import { IconLink } from '@tabler/icons-react';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { useCluster } from '../clusters/index.ts';
import { useEvent, useEvents, type BrokerEventView } from './api.ts';
import { useActionHost } from '../../kernel/actions/hostContext.ts';
import { absoluteHref, clusterHref } from '../../kernel/routing/href.ts';
import { useClusterStream } from '../../kernel/stream/useClusterStream.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Page } from '../../ui/Page.tsx';
import { Pager } from '../../ui/Pager.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import styles from './EventsView.module.css';
import { eventColumns, occurredAt, subjectOf } from './columns.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { useFilterShortcut } from '../../kernel/keyboard/filterShortcut.ts';

const LIVE_BUFFER_MAX = 500;

const PAGE_SIZE = 100;

const eventKey = (e: BrokerEventView) => String(e.seq);

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

function CopyEventLink({ clusterId, seq }: Readonly<{ clusterId: string; seq: number }>) {
  const host = useActionHost();
  return (
    <ActionMenuItem
      label="Copy link"
      icon={<IconLink size={16} aria-hidden />}
      onSelect={() => host.copy(absoluteHref(clusterHref(clusterId, 'events', { event: seq })), 'link to the event')}
    />
  );
}

type EventsSearch = {
  type?: string;
  address?: string;
  event?: number;
  page?: number;
};

type Notifications = NonNullable<ReturnType<typeof useCluster>['data']>['capabilities']['notifications'];

/**
 * The live feed: events pushed on the `events` topic, newest first, capped. The topic carries each
 * event itself — there is no resource behind it to refetch — so the frame goes straight into the buffer.
 */
function useLiveEvents(clusterId: string, live: boolean): BrokerEventView[] {
  const [buffer, setBuffer] = useState<BrokerEventView[]>([]);
  const onFrame = useCallback((topic: string, data: string) => {
    if (topic !== 'events') return;
    let e: BrokerEventView;
    try {
      e = JSON.parse(data) as BrokerEventView;
    } catch {
      return; // malformed frame — ignore
    }
    setBuffer((prev) => (prev.some((x) => x.seq === e.seq) ? prev : [e, ...prev].slice(0, LIVE_BUFFER_MAX)));
  }, []);
  useClusterStream(clusterId, live ? ['events'] : [], onFrame);
  return buffer;
}

/** Notifications not available: name the gap, show the broker.xml, infer nothing. */
function NotificationsUnavailable({ notifications }: Readonly<{ notifications: Notifications }>) {
  return (
    <Stack gap="sm">
      <Title order={3}>Events</Title>
      <Alert variant="light" title="Live events not available">
        {notifications.reason}
      </Alert>
      {notifications.brokerXmlSnippet ? <CodeHighlight code={notifications.brokerXmlSnippet} language="xml" /> : null}
    </Stack>
  );
}

/** Why the grid is empty: the filters, nodes that did not answer, or genuinely nothing recorded yet. */
function EventsEmpty({
  filtered,
  unreachable,
  onClearFilters,
}: Readonly<{ filtered: boolean; unreachable: string[]; onClearFilters: () => void }>) {
  if (filtered) {
    return (
      <EmptyState
        kind="filtered"
        title="No event matches these filters"
        description="Events are recorded on this cluster, but none has this type or address. Clear the filters to see them all."
        onClearFilters={onClearFilters}
      />
    );
  }
  if (unreachable.length > 0) {
    return (
      <EmptyState
        kind="unreachable"
        title={
          unreachable.length === 1
            ? `${unreachable[0]} could not be reached`
            : `${unreachable.length} nodes could not be reached`
        }
        description="Events from a node that does not answer are missing, so this is an incomplete view rather than a quiet cluster."
        nodes={unreachable}
      />
    );
  }
  return (
    <EmptyState
      kind="empty"
      title="No broker events yet"
      description="A broker event is a notification a broker raises when a consumer, session, connection or binding is created or closed. Studio records them as they arrive and keeps them for a limited time, so the first appears here as soon as a client connects or consumes. Leave Live on to watch them as they happen."
    />
  );
}

/** The open event's detail, or the reason it cannot be shown (loading, gone, failed to load). */
function EventDetail({
  selected,
  offPage,
}: Readonly<{ selected: BrokerEventView | null; offPage: ReturnType<typeof useEvent> }>) {
  if (!selected) {
    if (offPage.isPending) return <Skeleton height={28} />;
    if (offPage.error?.status === 404) {
      return (
        <Alert variant="light" title="This event no longer exists">
          Broker events are kept for a limited time, so retention may have removed it, or the link names an event of
          another cluster.
        </Alert>
      );
    }
    if (offPage.isError) return <ErrorState error={offPage.error} onRetry={() => void offPage.refetch()} />;
    return null;
  }
  return (
    <Stack gap="xs">
      <Text size="xs" c="dimmed">
        {occurredAt(selected)} · {selected.address ?? 'no address'} · {subjectOf(selected)}
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
  );
}

/** The live buffer merged over the fetched history, newest first, de-duplicated on seq. */
function mergeLive(history: BrokerEventView[], buffer: BrokerEventView[], showLive: boolean): BrokerEventView[] {
  if (!showLive || buffer.length === 0) return history;
  const seen = new Set(buffer.map((e) => e.seq));
  return [...buffer, ...history.filter((e) => !seen.has(e.seq))];
}

/** The events screen: this cluster's activemq.notifications history, newest first. */
export function EventsView() {
  // `/` focuses this view's filter (ADR-0109).
  const filterRef = useRef<HTMLInputElement>(null);
  useFilterShortcut(filterRef);
  // Absolute timestamps here read the display zone from module state, so this
  // subscribes the view to a zone change (`app/timezone.ts`).
  const zone = useDisplayZone();
  const columns = useMemo(() => eventColumns(zone), [zone]);
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as EventsSearch;
  const navigate = useNavigate();

  const cluster = useCluster(clusterId);
  const notifications = cluster.data?.capabilities.notifications;

  const [address, setAddress] = useState(search.address ?? '');
  const [debouncedAddress] = useDebouncedValue(address, 250);
  const page = search.page ?? 1;

  const [live, setLive] = useState(true);
  const buffer = useLiveEvents(clusterId, live);

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

  // The open event is an address, not a copy: held in the URL as its id and looked up in the rows
  // each render. A shared link can name an event that is not on the loaded page; it is fetched by
  // id then, rather than the link silently opening nothing.
  const setOpen = (e: BrokerEventView | null) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, event: e?.seq }),
    });

  const total = query.data?.count ?? 0;
  const dropped = query.data?.dropped ?? 0;

  // On page 1 with no filter, merge the live buffer over the fetched history.
  const historyData = query.data?.data;
  const rows = useMemo(
    () => mergeLive(historyData ?? [], buffer, page === 1 && !search.type && !debouncedAddress),
    [buffer, historyData, page, search.type, debouncedAddress],
  );

  // A live feed prepends rows, which moves the content under a reader who has scrolled away from the
  // top. Until they return, the grid keeps the rows it had; a new filter or page starts afresh.
  const holdKey = `${page}|${search.type ?? ''}|${debouncedAddress}`;
  const [held, setHeld] = useState<{ key: string; rows: BrokerEventView[] } | null>(null);
  const shown = held?.key === holdKey ? held.rows : rows;
  const onAtTopChange = (atTop: boolean) =>
    setHeld((prev) => (atTop ? null : prev?.key === holdKey ? prev : { key: holdKey, rows }));

  const unreachable = (cluster.data?.topology.nodes ?? [])
    .flatMap((n) => n.endpoints)
    .filter((e) => e.lastError)
    .map((e) => e.name);
  const filtered = Boolean(search.type || debouncedAddress);
  const clearFilters = () => {
    setAddress('');
    void setParam({ type: undefined, address: undefined });
  };

  const onPage = rows.find((e) => e.seq === search.event);
  const offPage = useEvent(clusterId, !onPage ? search.event : undefined);
  const selected = onPage ?? offPage.data ?? null;

  // Notifications not available: name the gap, show the broker.xml, infer nothing
  // (same stance as DlqView on address settings). All hooks run above this.
  if (notifications && notifications.status !== 'AVAILABLE') {
    return <NotificationsUnavailable notifications={notifications} />;
  }

  return (
    <Page fill>
      <Group justify="space-between" align="flex-end">
        <Title order={3}>Events</Title>
        <Switch size="xs" label="Live" checked={live} onChange={(e) => setLive(e.currentTarget.checked)} />
      </Group>

      {dropped > 0 ? (
        <Alert variant="light" title="Some events were dropped">
          {dropped} notification{dropped === 1 ? ' has' : 's have'} been dropped for this cluster because they arrived
          faster than the write buffer could be flushed. Raise <code>events.buffer-size</code> in settings if this
          persists.
        </Alert>
      ) : null}

      <Toolbar
        label="Event filters"
        start={
          <>
            <Select
              label="Filter by type"
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
              ref={filterRef}
              label="Filter by address"
              placeholder="Filter by address"
              value={address}
              onChange={(e) => setAddress(e.currentTarget.value)}
              onBlur={() => setParam({ address: debouncedAddress || undefined })}
              size="xs"
              w={220}
            />
          </>
        }
      />

      <DataTable
        label="Broker events"
        storageKey="events"
        height="fill"
        columns={columns}
        data={shown}
        rowKey={eventKey}
        loading={query.isPending}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        onRowClick={setOpen}
        onAtTopChange={onAtTopChange}
        toolbar={{
          end: (
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
          ),
        }}
        rowMenu={{
          label: (e) => `${e.type} at ${occurredAt(e)}`,
          render: (e) => <CopyEventLink clusterId={clusterId} seq={e.seq} />,
        }}
        empty={<EventsEmpty filtered={filtered} unreachable={unreachable} onClearFilters={clearFilters} />}
      />

      <Drawer
        opened={search.event !== undefined}
        onClose={() => setOpen(null)}
        position="right"
        size="lg"
        title={selected ? selected.type : 'Event'}
      >
        {search.event === undefined ? null : <EventDetail selected={selected} offPage={offPage} />}
      </Drawer>
    </Page>
  );
}
