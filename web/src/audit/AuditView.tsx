import { useState } from 'react';
import {
  Alert,
  Badge,
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
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { useAudit, useUsers, type AuditEventView } from '../api/client.ts';
import { useCan } from '../auth/useCan.ts';
import { VirtualTable, type GridColumn } from '../grid/VirtualTable.tsx';
import { Pager } from '../grid/Pager.tsx';
import { absoluteLabel } from '../app/time.ts';
import { useDisplayZone } from '../app/timezone.ts';

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

/** The audit-log screen (non-negotiable #3): every mutating call, filterable, newest first. */
export function AuditView() {
  // Absolute timestamps here read the display zone from module state, so this
  // subscribes the view to a zone change (`app/timezone.ts`).
  useDisplayZone();
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as {
    user?: string;
    action?: string;
    outcome?: string;
    page?: number;
  };
  const navigate = useNavigate();
  const { can } = useCan();
  const canListUsers = can('user:admin');
  const users = useUsers(canListUsers);

  const [selected, setSelected] = useState<AuditEventView | null>(null);
  const [user, setUser] = useState(search.user ?? '');
  const [debouncedUser] = useDebouncedValue(user, 250);
  const page = search.page ?? 1;

  const setParam = (patch: Record<string, unknown>) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, ...patch, page: undefined }),
    });

  // The Select commits to the URL immediately (a discrete choice needs no debounce);
  // the free-text fallback commits its own debounced value the same way on blur —
  // either way, the URL's `search.user` is the single source of truth for the query.
  const query = useAudit(clusterId, {
    user: search.user,
    action: search.action,
    outcome: search.outcome,
    page,
    size: PAGE_SIZE,
  });

  const rows = query.data?.data ?? [];
  const total = query.data?.count ?? 0;

  return (
    <Stack gap="sm">
      {/* The count and position live in the pager, stated once. */}
      <Title order={3}>Audit log</Title>

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
          data={[
            'SEND_MESSAGE',
            'MOVE_MESSAGES',
            'RETRY_MESSAGES',
            'DELETE_MESSAGES',
            'EXPIRE_MESSAGES',
            'PURGE_QUEUE',
            'REGISTER_CLUSTER',
            'REDISCOVER_CLUSTER',
            'DELETE_CLUSTER',
          ]}
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
          No audit events match. Every message operation, purge and cluster change is recorded here
          the moment it runs.
        </Text>
      ) : (
        <VirtualTable
          columns={columns}
          data={rows}
          rowKey={auditKey}
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
        label="audit events"
      />

      <Drawer
        opened={selected !== null}
        onClose={() => setSelected(null)}
        position="right"
        size="lg"
        title={selected ? selected.action : ''}
      >
        {selected ? (
          <Stack gap="xs">
            <Text size="xs" c="dimmed">
              {at(selected)} · {selected.username ?? 'anonymous'} ·{' '}
              {selected.targetName ?? 'no target'}
              {selected.dryRun ? ' · dry run' : ''}
            </Text>
            {selected.error ? (
              <Text size="xs" c="red">
                {selected.error}
              </Text>
            ) : null}
            {selected.params ? <Code block>{selected.params}</Code> : null}
            <Text size="xs" c="dimmed">
              request {selected.requestId ?? '—'} · from {selected.sourceIp ?? '—'}
            </Text>
          </Stack>
        ) : null}
      </Drawer>

    </Stack>
  );
}
