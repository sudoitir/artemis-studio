import { useMemo, useRef, useState } from 'react';
import { Alert, Button, Code, Drawer, Group, Select, Skeleton, Stack, Text, TextInput, Title } from '@mantine/core';
import { IconLink } from '@tabler/icons-react';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { useAudit, useAuditEvent, type AuditEventView } from './api.ts';
import { useUsers } from '../security/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { useActionHost } from '../../kernel/actions/hostContext.ts';
import { absoluteHref, clusterHref } from '../../kernel/routing/href.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Page } from '../../ui/Page.tsx';
import { Pager } from '../../ui/Pager.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { at, auditColumns } from './columns.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { useFilterShortcut } from '../../kernel/keyboard/filterShortcut.ts';

const PAGE_SIZE = 100;

function auditKey(e: AuditEventView): string {
  return `${e.ts}-${e.action}-${e.requestId}`;
}

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
          label="User"
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
        label="Action"
        placeholder="Any action"
        size="xs"
        w={190}
        clearable
        value={search.action ?? null}
        onChange={(v) => setParam({ action: v || undefined })}
        data={ACTIONS}
      />
      <Select
        label="Outcome"
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

/** Why the grid is empty: a filter excludes every event, or nothing has been recorded yet. */
function AuditEmpty({ filtered, onClearFilters }: Readonly<{ filtered: boolean; onClearFilters: () => void }>) {
  if (filtered) {
    return (
      <EmptyState
        kind="filtered"
        title="No audit event matches these filters"
        description="Events are recorded on this cluster, but none has this user, action or outcome, or belongs to the run you are looking at. Clear the filters to see them all."
        onClearFilters={onClearFilters}
      />
    );
  }
  return (
    <EmptyState
      kind="empty"
      title="No audit events yet"
      description="The audit log records every message operation, purge and cluster change the moment it runs, with who did it and how it ended. Nothing has been recorded on this cluster yet; run an operation and it appears here."
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
        <Alert variant="light" title="This audit event no longer exists">
          No audit event with this id exists on this cluster. Check the link, or ask whoever shared it to copy it again.
        </Alert>
      );
    }
    if (offPage.isError) return <ErrorState error={offPage.error} onRetry={() => void offPage.refetch()} />;
    return null;
  }
  return (
    <Stack gap="xs">
      <Text size="xs" c="dimmed">
        {at(selected)} · {selected.username ?? 'anonymous'} · {selected.targetName ?? 'no target'}
        {selected.dryRun ? ' · dry run' : ''}
      </Text>
      {selected.error ? (
        <Text size="xs" c="var(--as-danger)">
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
  const zone = useDisplayZone();
  const columns = useMemo(() => auditColumns(zone), [zone]);
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

  // The user field keeps what was typed in it, so clearing the filters starts it afresh.
  const [filterEpoch, setFilterEpoch] = useState(0);
  const filtered = Boolean(search.user || search.action || search.outcome || search.parentId != null);
  const clearFilters = () => {
    setFilterEpoch((n) => n + 1);
    void setParam({ user: undefined, action: undefined, outcome: undefined, parentId: undefined });
  };

  return (
    <Page fill>
      <Title order={3}>Audit log</Title>

      <Toolbar label="Audit filters" start={<AuditFilters key={filterEpoch} search={search} setParam={setParam} />} />

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

      <DataTable
        label="Audit events"
        storageKey="audit"
        height="fill"
        columns={columns}
        data={rows}
        rowKey={auditKey}
        loading={query.isPending}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        onRowClick={setOpen}
        toolbar={{
          // The count and position live in the pager, stated once.
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
              label="audit events"
            />
          ),
        }}
        rowMenu={{
          label: (e) => `${e.action} at ${at(e)}`,
          render: (e) => <CopyAuditLink clusterId={clusterId} id={e.id} />,
        }}
        empty={<AuditEmpty filtered={filtered} onClearFilters={clearFilters} />}
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
    </Page>
  );
}
