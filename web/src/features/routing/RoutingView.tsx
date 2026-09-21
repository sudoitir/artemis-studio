import { useEffect, useState } from 'react';
import { Alert, Badge, Button, Group, Modal, Skeleton, Stack, Tabs, Text, TextInput } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { useBridges, useDiverts, type BridgeView, type DivertView } from './api.ts';
import type { RoutingSearch } from './feature.ts';
import { useSlot } from '../../kernel/slots.ts';
import { VirtualTable, type GridColumn } from '../../ui/VirtualTable.tsx';
import { Pager } from '../../ui/Pager.tsx';
import { BrokerXmlRemedy, DeleteDivertAction, CreateDivertAction, DRIFT_SENTENCE } from './DivertActions.tsx';
import classes from './RoutingView.module.css';

const PAGE_SIZE = 200;

type Tab = 'diverts' | 'bridges';

/**
 * A Studio-created divert's ownership, as a control rather than a hover title: it opens the
 * broker.xml that would make the deployed configuration carry it, reachable from the keyboard.
 */
function StudioOwned({ divert }: { divert: DivertView }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button size="compact-xs" variant="subtle" color="gray" onClick={() => setOpen(true)}>
        Studio — not in broker.xml
      </Button>
      <Modal opened={open} onClose={() => setOpen(false)} title={`"${divert.name}" is not in broker.xml`} size="lg">
        <Stack gap="sm">
          <Text size="sm">{DRIFT_SENTENCE}</Text>
          {divert.brokerXml ? <BrokerXmlRemedy xml={divert.brokerXml} /> : null}
        </Stack>
      </Modal>
    </>
  );
}

/**
 * A divert's direction as one object: source, arrow, destination.
 *
 * <p>The question this view exists to answer is "where does traffic on this
 * address go", and reconstructing that from two separate columns is exactly the
 * work the routing spec says an operator should not have to do. The accessible
 * name spells the relationship out, because the arrow is a glyph.
 */
function Direction({ from, to }: { from: string; to: string }) {
  return (
    <div className={classes.direction} aria-label={`from ${from} to ${to}`}>
      <Text size="xs" className={classes.endpoint} title={from}>
        {from}
      </Text>
      <span className={classes.arrow} aria-hidden="true">
        →
      </span>
      <Text size="xs" className={classes.endpoint} title={to}>
        {to}
      </Text>
    </div>
  );
}

/**
 * The columns of the divert view.
 *
 * <p>There is no origin column. Artemis records nothing saying whether a divert
 * came from broker.xml or from a management call, and exposes no way to read
 * configured-but-undeployed diverts, so the product does not guess (ADR-0065).
 * What it can say honestly is which diverts are its own, and it says that.
 */
function divertColumns(clusterId: string): GridColumn<DivertView>[] {
  return [
    { id: 'name', header: 'Name', accessor: (r) => r.name, sortKey: 'name' },
    {
      id: 'direction',
      header: 'Routes',
      accessor: (r) => `${r.address} ${r.forwardingAddress}`,
      cell: (r) => <Direction from={r.address} to={r.forwardingAddress} />,
    },
    {
      id: 'exclusive',
      header: 'Effect',
      accessor: (r) => (r.exclusive ? 'takes' : 'copies'),
      width: 150,
      // In words, never by colour alone: the difference between traffic being
      // duplicated and traffic being taken away is the most consequential fact
      // in this table.
      cell: (r) => (
        <Text size="xs" title={r.exclusive ? 'Exclusive divert' : 'Non-exclusive divert'}>
          {r.exclusive ? 'takes the message' : 'copies the message'}
        </Text>
      ),
    },
    { id: 'filter', header: 'Filter', accessor: (r) => r.filter ?? '', width: 180 },
    {
      id: 'owner',
      header: 'Created by',
      accessor: (r) => r.owner ?? '',
      width: 170,
      cell: (r) =>
        r.owner === 'MESSAGE_CAPTURE' ? (
          <Badge size="xs" variant="light" color="gray" title="Serves a message capture subscription">
            message capture
          </Badge>
        ) : r.owner === 'OPERATOR' ? (
          <StudioOwned divert={r} />
        ) : (
          <Text size="xs" c="dimmed" title="Studio has no record of creating this divert. That is not a claim about where it came from.">
            not recorded
          </Text>
        ),
    },
    {
      id: 'nodes',
      header: 'Nodes',
      accessor: (r) => `${r.nodesPresent}/${r.nodesTotal}`,
      width: 90,
      numeric: true,
      cell: (r) => (
        <Text
          size="xs"
          title={r.perNode.map((n) => n.nodeName).join(', ')}
        >
          {r.nodesPresent}/{r.nodesTotal}
        </Text>
      ),
    },
    {
      id: 'action',
      header: 'Action',
      accessor: () => '',
      width: 170,
      cell: (r) => <DeleteDivertAction clusterId={clusterId} divert={r} />,
    },
  ];
}

/**
 * What the live view of a bridge reports. Declaring, changing and removing one is
 * the declaration's job (ADR-0091), reached on the Builder tab — this
 * table reads every serving node and has no write of its own.
 */
const BRIDGE_COLUMNS: GridColumn<BridgeView>[] = [
  { id: 'name', header: 'Name', accessor: (r) => r.name, sortKey: 'name' },
  {
    id: 'direction',
    header: 'Routes',
    accessor: (r) => `${r.queueName ?? ''} ${r.forwardingAddress ?? ''}`,
    cell: (r) => <Direction from={r.queueName ?? '(unnamed queue)'} to={r.forwardingAddress ?? '(the target broker)'} />,
  },
  {
    id: 'state',
    header: 'State',
    accessor: (r) => (r.connected ? 'connected' : r.started ? 'started' : 'stopped'),
    width: 220,
    // Started and connected are different facts. A bridge that is started and
    // cannot reach its target is the state "is this bridge running" is asking
    // about, and collapsing the two would answer the wrong question.
    cell: (r) => (
      <Text size="xs" c={r.started && !r.connected ? undefined : 'dimmed'}>
        {r.connected
          ? 'running and connected'
          : r.started
            ? 'started, not connected to its target'
            : 'not started'}
      </Text>
    ),
  },
  {
    id: 'pending',
    header: 'Pending',
    accessor: (r) => r.messagesPendingAcknowledgement,
    numeric: true,
    width: 110,
  },
  { id: 'acked', header: 'Acked', accessor: (r) => r.messagesAcknowledged, numeric: true, width: 110 },
  {
    id: 'nodes',
    header: 'Nodes',
    accessor: (r) => `${r.nodesPresent}/${r.nodesTotal}`,
    width: 90,
    numeric: true,
    cell: (r) => <Text size="xs" title={r.perNode.map((n) => n.nodeName).join(', ')}>{r.nodesPresent}/{r.nodesTotal}</Text>,
  },
];

/**
 * A cluster's routing: what copies or takes its traffic, what carries it to another broker, and
 * the tabs other features contribute through `routing.tabs` — the routing builder among them.
 *
 * <p>Diverts and Bridges are live reads across every serving node, merged. Which tab is open,
 * the filter and the page all live in the URL, so a routing view can be shared as it was seen.
 */
export function RoutingView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as RoutingSearch;
  const navigate = useNavigate();
  const contributed = useSlot('routing.tabs');
  const slot = contributed.find((c) => c.id === search.tab);
  const tab: string = slot ? slot.id : search.tab === 'bridges' ? 'bridges' : 'diverts';

  // Switching tab drops whatever the previous tab kept in the URL, so a filter or an open editor
  // never follows the operator onto a view it does not belong to.
  const setTab = (next: string | null) =>
    navigate({
      to: '.',
      search: () => ({ tab: next && next !== 'diverts' ? next : undefined }),
    });

  return (
    <Stack gap="sm">
      <Tabs value={tab} onChange={setTab}>
        <Tabs.List>
          <Tabs.Tab value="diverts">Diverts</Tabs.Tab>
          <Tabs.Tab value="bridges">Bridges</Tabs.Tab>
          {contributed.map(({ id, title }) => (
            <Tabs.Tab key={id} value={id}>
              {title}
            </Tabs.Tab>
          ))}
        </Tabs.List>
      </Tabs>

      {slot ? (
        <slot.Component clusterId={clusterId} />
      ) : (
        <RoutingListing
          clusterId={clusterId}
          tab={tab === 'bridges' ? 'bridges' : 'diverts'}
          // By tab id, not by import: the builder belongs to brokerconfig, and naming its tab adds
          // no dependency edge between the two.
          hasBuilder={contributed.some((c) => c.id === 'builder')}
        />
      )}
    </Stack>
  );
}

/** The Diverts or the Bridges tab: one live, filtered, paged listing. */
function RoutingListing({ clusterId, tab, hasBuilder }: { clusterId: string; tab: Tab; hasBuilder: boolean }) {
  const search = useSearch({ strict: false }) as RoutingSearch;
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

  const params = { q: search.q, sort: search.sort, page, size: PAGE_SIZE };
  const diverts = useDiverts(clusterId, tab === 'diverts' ? params : { size: 1 });
  const bridges = useBridges(clusterId, tab === 'bridges' ? params : { size: 1 });
  const query = tab === 'diverts' ? diverts : bridges;

  const setSearch = (patch: Record<string, unknown>) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...patch }) });

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
    <>
      <Group justify="space-between">
        <TextInput
          placeholder="Filter by address or name"
          aria-label="Filter by address or name"
          value={filter}
          onChange={(e) => setFilter(e.currentTarget.value)}
          w={280}
          size="xs"
        />
        {tab === 'diverts' ? <CreateDivertAction clusterId={clusterId} /> : null}
      </Group>

      {query.isPending && rows.length === 0 ? (
        <Stack gap={4}>
          {Array.from({ length: 8 }).map((_, i) => (
            <Skeleton key={i} height={30} />
          ))}
        </Stack>
      ) : tab === 'diverts' ? (
        <VirtualTable
          columns={divertColumns(clusterId)}
          data={rows as DivertView[]}
          sort={search.sort}
          onSortChange={(sort) => setSearch({ sort, page: undefined })}
          rowKey={(r) => `${r.name}:${r.address}:${r.forwardingAddress}`}
          emptyLabel={
            <Text size="sm">
              {search.q
                ? 'No divert matches this filter. Clear it to see every divert on the cluster.'
                : 'No diverts. A divert copies — or, when exclusive, redirects — the messages arriving at one address to another, without the producers knowing.'}
            </Text>
          }
        />
      ) : (
        <VirtualTable
          columns={BRIDGE_COLUMNS}
          data={rows as BridgeView[]}
          sort={search.sort}
          onSortChange={(sort) => setSearch({ sort, page: undefined })}
          rowKey={(r) => r.name}
          emptyLabel={
            <Text size="sm">
              {search.q
                ? 'No bridge matches this filter. Clear it to see every bridge on the cluster.'
                : hasBuilder
                  ? 'No bridges. A bridge forwards a queue to an address on another broker. Declare one on the Builder tab and apply it with the rest of the declaration; the plan names the hazard, because a bridge rewires how this cluster reaches other brokers.'
                  : 'No bridges. A bridge forwards a queue to an address on another broker, and is declared in the cluster configuration — which is not enabled on this Studio.'}
            </Text>
          }
        />
      )}

      <Pager
        page={page}
        pageSize={PAGE_SIZE}
        total={total}
        onChange={(next) => setSearch({ page: next > 1 ? next : undefined })}
        label={tab}
      />
    </>
  );
}
