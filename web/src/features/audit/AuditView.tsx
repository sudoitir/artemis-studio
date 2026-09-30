import { useRef, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Code,
  Drawer,
  Group,
  Select,
  Skeleton,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { IconLink } from '@tabler/icons-react';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { useAudit, useAuditEvent, type AuditEventView } from './api.ts';
import { useUsers } from '../security/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { useActionHost } from '../../kernel/actions/hostContext.ts';
import { absoluteHref, clusterHref } from '../../kernel/routing/href.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { VirtualTable, type GridColumn } from '../../ui/VirtualTable.tsx';
import { Pager } from '../../ui/Pager.tsx';
import { absoluteLabel } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { useFilterShortcut } from '../../kernel/keyboard/filterShortcut.ts';

const PAGE_SIZE = 100;

/** Status word + tone — colour is never the sole signal (non-negotiable #6). */
function outcome(o: string): { word: string; color: string } {
  if (o === 'SUCCESS') return { word: 'success', color: 'green' };
  if (o === 'FAILURE') return { word: 'failure', color: 'red' };
  return { word: 'pending', color: 'yellow' };
}

function auditKey(e: AuditEventView): string {
  return `${e.ts}-${e.action}-${e.requestId}`;
}

function at(e: AuditEventView): string {
  return absoluteLabel(e.ts);
}

/**
 * The params and error payload used to expand inline under the row; a
 * virtualised grid has no row to expand under, and the drawer is the better home
 * for a payload that is frequently taller than the viewport.
 */
const columns: GridColumn<AuditEventView>[] = [
  { id: 'time', header: 'Time', accessor: at, width: 200 },
  { id: 'user', header: 'User', accessor: (e) => e.username ?? 'anonymous', width: 160 },
  {
    id: 'action',
    header: 'Action',
    accessor: (e) => e.action,
    cell: (e) => (
      <Text size="xs" ff="monospace">
        {e.action}
      </Text>
    ),
  },
  {
    id: 'target',
    header: 'Target',
    accessor: (e) => e.targetName ?? '—',
    cell: (e) => (
      <Text size="xs">
        {e.targetName ?? '—'}
        {e.dryRun ? (
          <Text span size="xs" c="dimmed">
            {' '}
            · dry run
          </Text>
        ) : null}
      </Text>
    ),
  },
  {
    id: 'count',
    header: 'Count',
    accessor: (e) => e.affectedCount ?? '—',
    numeric: true,
    width: 90,
  },
  {
    id: 'outcome',
    header: 'Outcome',
    accessor: (e) => outcome(e.outcome).word,
    width: 110,
    cell: (e) => {
      const oc = outcome(e.outcome);
      return (
        <Badge size="xs" variant="light" color={oc.color}>
          {oc.word}
        </Badge>
      );
    },
  },
];

function CopyAuditLink({ clusterId, id }: Readonly<{ clusterId: string; id: number }>) {
  const host = useActionHost();
  return (
    <ActionMenuItem
      label="Copy link"
      icon={<IconLink size={16} aria-hidden />}
      onSelect={() =>
        host.copy(absoluteHref(clusterHref(clusterId, 'audit', { event: id })), 'link to the audit event')
      }
    />
  );
}

type AuditSearch = {
  user?: string;
  action?: string;
  outcome?: string;
  parentId?: number;
  event?: number;
  page?: number;
};

type SetParam = (patch: Record<string, unknown>) => unknown;

const ACTIONS = [
  'SEND_MESSAGE',
  'MOVE_MESSAGES',
  'RETRY_MESSAGES',
  'DELETE_MESSAGES',
  'EXPIRE_MESSAGES',
  'PURGE_QUEUE',
  'REGISTER_CLUSTER',
  'REDISCOVER_CLUSTER',
  'DELETE_CLUSTER',
];

/** The user, action and outcome filters; each commits to the URL, the single source of truth. */
function AuditFilters({ search, setParam }: Readonly<{ search: AuditSearch; setParam: SetParam }>) {
  // `/` focuses this view's filter (ADR-0109).
  const filterRef = useRef<HTMLInputElement>(null);
  useFilterShortcut(filterRef);
  const { can } = useCan();
  const canListUsers = can('user:admin');
  const users = useUsers(canListUsers);

  const [user, setUser] = useState(search.user ?? '');
  const [debouncedUser] = useDebouncedValue(user, 250);

  // The Select commits to the URL immediately (a discrete choice needs no debounce);
  // the free-text fallback commits its own debounced value the same way on blur —
  // either way, the URL's `search.user` is the single source of truth for the query.
  return (
    <Group gap="xs">
      {canListUsers ? (
        <Select
          placeholder="Any user"
          size="xs"
          w={180}
          clearable
          searchable
          value={search.user ?? null}
          onChange={(v) => setParam({ user: v || undefined })}
          data={(users.data ?? []).map((u) => u.username)}
        />
      ) : (
        <TextInput
          ref={filterRef}
          label="Filter by user"
          placeholder="Filter by user"
          value={user}
          onChange={(e) => setUser(e.currentTarget.value)}
          onBlur={() => setParam({ user: debouncedUser || undefined })}
          size="xs"
          w={180}
        />
      )}
      <Select
        placeholder="Any action"
        size="xs"
        w={190}
        clearable
        value={search.action ?? null}
        onChange={(v) => setParam({ action: v || undefined })}
        data={ACTIONS}
      />
      <Select
        placeholder="Any outcome"
        size="xs"
        w={150}
        clearable
        value={search.outcome ?? null}
        onChange={(v) => setParam({ outcome: v || undefined })}
        data={['SUCCESS', 'FAILURE', 'PENDING']}
      />
    </Group>
  );
}

/** The event table, or what stands in for it while loading, on error, or when nothing matches. */
function AuditTable({
  query,
  rows,
  clusterId,
  onOpen,
}: Readonly<{
  query: ReturnType<typeof useAudit>;
  rows: AuditEventView[];
  clusterId: string;
  onOpen: (e: AuditEventView) => void;
}>) {
  if (query.isError) {
    return (
      <Alert color="red" variant="light" title={query.error.title}>
        {query.error.message}
      </Alert>
    );
  }
  if (query.isPending && rows.length === 0) {
    return (
      <Stack gap={4}>
        {Array.from({ length: 12 }).map((_, i) => (
          <Skeleton key={i} height={28} />
        ))}
      </Stack>
    );
  }
  if (rows.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        No audit events match. Every message operation, purge and cluster change is recorded here the moment it runs.
      </Text>
    );
  }
  return (
    <VirtualTable
      label="Audit events"
      storageKey="audit"
      columns={columns}
      data={rows}
      rowKey={auditKey}
      onRowClick={onOpen}
      rowMenu={{
        label: (e) => `${e.action} at ${at(e)}`,
        render: (e) => <CopyAuditLink clusterId={clusterId} id={e.id} />,
      }}
    />
  );
}

/** The open event's detail, or the reason it cannot be shown (loading, gone, failed to load). */
function AuditEventDetail({
  selected,
  offPage,
  setParam,
}: Readonly<{
  selected: AuditEventView | null;
  offPage: ReturnType<typeof useAuditEvent>;
  setParam: SetParam;
}>) {
  if (!selected) {
    if (offPage.isPending) return <Skeleton height={28} />;
    if (offPage.error?.status === 404) {
      return (
        <Alert color="blue" variant="light" title="This audit event no longer exists">
          No audit event with this id exists on this cluster. Check the link, or ask whoever shared it to copy it again.
        </Alert>
      );
    }
    if (offPage.isError) {
      return (
        <Alert color="red" variant="light" title={offPage.error.title}>
          {offPage.error.message}
        </Alert>
      );
    }
    return null;
  }
  return (
    <Stack gap="xs">
      <Text size="xs" c="dimmed">
        {at(selected)} · {selected.username ?? 'anonymous'} · {selected.targetName ?? 'no target'}
        {selected.dryRun ? ' · dry run' : ''}
      </Text>
      {selected.error ? (
        <Text size="xs" c="red">
          {selected.error}
        </Text>
      ) : null}
      {selected.params ? <Code block>{selected.params}</Code> : null}
      {selected.parentId != null ? (
        <Button
          size="xs"
          variant="light"
          onClick={() => {
            void setParam({ parentId: selected.parentId, event: undefined });
          }}
        >
          Show the operation this belongs to, with all its parts
        </Button>
      ) : null}
      {selected.action.startsWith('bulk.') ? (
        <Button
          size="xs"
          variant="light"
          onClick={() => {
            void setParam({ parentId: selected.id, event: undefined });
          }}
        >
          Show the event for each queue in this run
        </Button>
      ) : null}
      <Text size="xs" c="dimmed">
        request {selected.requestId ?? '—'} · from {selected.sourceIp ?? '—'}
      </Text>
    </Stack>
  );
}

/** The audit-log screen (non-negotiable #3): every mutating call, filterable, newest first. */
export function AuditView() {
  // Absolute timestamps here read the display zone from module state, so this
  // subscribes the view to a zone change (`app/timezone.ts`).
  useDisplayZone();
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as AuditSearch;
  const navigate = useNavigate();
  const page = search.page ?? 1;

  const setParam = (patch: Record<string, unknown>) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, ...patch, page: undefined }),
    });

  const query = useAudit(clusterId, {
    user: search.user,
    action: search.action,
    outcome: search.outcome,
    parentId: search.parentId,
    page,
    size: PAGE_SIZE,
  });

  const rows = query.data?.data ?? [];
  // The open event is held in the URL as its id and looked up in the rows each render. A shared
  // link can name an event that is not on the loaded page; it is fetched by id then, rather than
  // the link silently opening nothing.
  const setOpen = (e: AuditEventView | null) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, event: e?.id }),
    });
  const onPage = rows.find((e) => e.id === search.event);
  const offPage = useAuditEvent(clusterId, !onPage ? search.event : undefined);
  const selected = onPage ?? offPage.data ?? null;
  const total = query.data?.count ?? 0;

  return (
    <Stack gap="sm">
      {/* The count and position live in the pager, stated once. */}
      <Title order={3}>Audit log</Title>

      <AuditFilters search={search} setParam={setParam} />

      {search.parentId != null ? (
        <Group gap="xs">
          <Text size="sm">
            Showing the events that belong to audit event {search.parentId}, such as each queue of a bulk run.
          </Text>
          <Button size="xs" variant="subtle" onClick={() => setParam({ parentId: undefined })}>
            Show every event
          </Button>
        </Group>
      ) : null}

      <AuditTable query={query} rows={rows} clusterId={clusterId} onOpen={setOpen} />

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
        label="audit events"
      />

      <Drawer
        opened={search.event !== undefined}
        onClose={() => setOpen(null)}
        position="right"
        size="lg"
        title={selected ? selected.action : 'Audit event'}
      >
        {search.event === undefined ? null : (
          <AuditEventDetail selected={selected} offPage={offPage} setParam={setParam} />
        )}
      </Drawer>
    </Stack>
  );
}
