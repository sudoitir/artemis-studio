import { useState } from 'react';
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
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { useAudit, type AuditEventView } from '../api/client.ts';

const PAGE_SIZE = 100;

/** Status word + tone — colour is never the sole signal (non-negotiable #6). */
function outcome(o: string): { word: string; color: string } {
  if (o === 'SUCCESS') return { word: 'success', color: 'green' };
  if (o === 'FAILURE') return { word: 'failure', color: 'red' };
  return { word: 'pending', color: 'yellow' };
}

function Row({ e }: { e: AuditEventView }) {
  const [open, setOpen] = useState(false);
  const oc = outcome(e.outcome);
  const expandable = Boolean(e.params || e.error);
  return (
    <>
      <Table.Tr
        onClick={expandable ? () => setOpen((v) => !v) : undefined}
        style={{ cursor: expandable ? 'pointer' : undefined }}
      >
        <Table.Td>
          <Text size="xs">{new Date(e.ts).toISOString().replace('T', ' ').replace('.000Z', 'Z')}</Text>
        </Table.Td>
        <Table.Td>
          <Text size="xs">{e.username ?? 'anonymous'}</Text>
        </Table.Td>
        <Table.Td>
          <Text size="xs" ff="monospace">
            {e.action}
          </Text>
        </Table.Td>
        <Table.Td>
          <Text size="xs">
            {e.targetName ?? '—'}
            {e.dryRun ? (
              <Text span size="xs" c="dimmed">
                {' '}
                · dry run
              </Text>
            ) : null}
          </Text>
        </Table.Td>
        <Table.Td ta="end">
          <Text size="xs">{e.affectedCount ?? '—'}</Text>
        </Table.Td>
        <Table.Td>
          <Badge size="xs" variant="light" color={oc.color}>
            {oc.word}
          </Badge>
        </Table.Td>
      </Table.Tr>
      {expandable ? (
        <Table.Tr>
          <Table.Td colSpan={6} p={0}>
            <Collapse expanded={open}>
              <Stack gap={4} p="xs">
                {e.error ? (
                  <Text size="xs" c="red">
                    {e.error}
                  </Text>
                ) : null}
                {e.params ? <Code block>{e.params}</Code> : null}
                <Text size="xs" c="dimmed">
                  request {e.requestId ?? '—'} · from {e.sourceIp ?? '—'}
                </Text>
              </Stack>
            </Collapse>
          </Table.Td>
        </Table.Tr>
      ) : null}
    </>
  );
}

/** The audit-log screen (non-negotiable #3): every mutating call, filterable, newest first. */
export function AuditView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as {
    user?: string;
    action?: string;
    outcome?: string;
    page?: number;
  };
  const navigate = useNavigate();

  const [user, setUser] = useState(search.user ?? '');
  const [debouncedUser] = useDebouncedValue(user, 250);
  const page = search.page ?? 1;

  const setParam = (patch: Record<string, unknown>) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, ...patch, page: undefined }),
    });

  const query = useAudit(clusterId, {
    user: debouncedUser || undefined,
    action: search.action,
    outcome: search.outcome,
    page,
    size: PAGE_SIZE,
  });

  const rows = query.data?.data ?? [];
  const total = query.data?.count ?? 0;
  const lastPage = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <Stack gap="sm">
      <Group justify="space-between" align="flex-end">
        <Title order={3}>Audit log</Title>
        <Text size="xs" c="dimmed">
          {total} event{total === 1 ? '' : 's'} · page {page} of {lastPage}
        </Text>
      </Group>

      <Group gap="xs">
        <TextInput
          placeholder="Filter by user"
          value={user}
          onChange={(e) => setUser(e.currentTarget.value)}
          onBlur={() => setParam({ user: debouncedUser || undefined })}
          size="xs"
          w={180}
        />
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
        <Table.ScrollContainer minWidth={720} type="native">
          <Table stickyHeader highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Time</Table.Th>
                <Table.Th>User</Table.Th>
                <Table.Th>Action</Table.Th>
                <Table.Th>Target</Table.Th>
                <Table.Th ta="end">Count</Table.Th>
                <Table.Th>Outcome</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((e) => (
                <Row key={`${e.ts}-${e.action}-${e.requestId}`} e={e} />
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
            onClick={() => navigate({ to: '.', search: (p: Record<string, unknown>) => ({ ...p, page: page - 1 > 1 ? page - 1 : undefined }) })}
          >
            Previous
          </Button>
          <Button
            size="xs"
            variant="default"
            disabled={page >= lastPage}
            onClick={() => navigate({ to: '.', search: (p: Record<string, unknown>) => ({ ...p, page: page + 1 }) })}
          >
            Next
          </Button>
        </Group>
      ) : null}
    </Stack>
  );
}
