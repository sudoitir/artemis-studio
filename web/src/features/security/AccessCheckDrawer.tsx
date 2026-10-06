import { useState } from 'react';
import { Button, Drawer, SegmentedControl, Select, Stack, Text, TextInput } from '@mantine/core';
import { IconSearch } from '@tabler/icons-react';

import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useClusters } from '../clusters/index.ts';
import { useAccessCheck, type AccessCheckView } from './api.ts';
import { accessColumns } from './columns.ts';
import { RoleGrants } from './RoleGrants.tsx';
import { useScopeLabel } from './scope.ts';

type Where = { clusterId: string | null; kind: 'QUEUE' | 'ADDRESS'; name: string };

const NOWHERE: Where = { clusterId: null, kind: 'QUEUE', name: '' };

const rowKey = (v: AccessCheckView) => v.action;

/** Where the check was made, in words. */
function placeText(where: Where, cluster: string | undefined): string {
  if (!where.clusterId) return 'Studio itself, with no cluster';
  const name = where.name.trim();
  const on = cluster ?? 'the cluster';
  const noun = where.kind === 'QUEUE' ? 'queue' : 'address';
  return name ? `${noun} ${name} on ${on}` : `${on} as a whole`;
}

/**
 * A user's access, checked for a place (operator-ui spec): every permission with whether the user holds it there
 * and each source that gives it, a role granted at a scope, a team role on the team that owns the queue or address,
 * or a share from the owning team. Below it, the roles the user holds.
 */
export function AccessCheckDrawer({
  user,
  onClose,
}: Readonly<{
  user: { id: string; username: string } | null;
  onClose: () => void;
}>) {
  return (
    <Drawer
      opened={user !== null}
      onClose={onClose}
      position="right"
      size="xl"
      title={user ? `Access check for ${user.username}` : ''}
    >
      {user ? (
        <Stack gap="xl">
          <AccessCheck userId={user.id} />
          <Section title="Role grants" headingLevel={3} description="The roles this user holds, at each scope.">
            <RoleGrants userId={user.id} />
          </Section>
        </Stack>
      ) : null}
    </Drawer>
  );
}

function AccessCheck({ userId }: Readonly<{ userId: string }>) {
  const clusters = useClusters();
  const scopeLabel = useScopeLabel();
  const [draft, setDraft] = useState<Where>(NOWHERE);
  const [asked, setAsked] = useState<Where>(NOWHERE);
  const [query, setQuery] = useState('');
  const result = useAccessCheck(userId, asked);

  const q = query.trim().toLowerCase();
  const rows = (result.data ?? []).filter((v) => q === '' || v.action.toLowerCase().includes(q));
  const allowed = (result.data ?? []).filter((v) => v.allowed).length;
  const columns = accessColumns({ scopeLabel });
  const askedCluster = clusters.data?.find((c) => c.id === asked.clusterId)?.name;

  return (
    <Section
      title="Access check"
      headingLevel={3}
      description="Choose a cluster, and a queue or address on it, to see who gives this user each permission there."
    >
      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          setAsked(draft);
        }}
      >
        <Stack gap="sm">
          <FieldRow>
            <Select
              label="Cluster"
              data={(clusters.data ?? []).map((c) => ({ value: c.id, label: c.name }))}
              value={draft.clusterId}
              onChange={(clusterId) => setDraft({ ...draft, clusterId, name: clusterId ? draft.name : '' })}
              placeholder={clusters.isPending ? 'Loading' : 'None: Studio itself'}
              nothingFoundMessage="No clusters"
              searchable
              clearable
            />
            <TextInput
              label="Queue or address"
              description={draft.clusterId ? 'Leave empty to check the cluster as a whole.' : 'Choose a cluster first.'}
              value={draft.name}
              disabled={!draft.clusterId}
              onChange={(e) => setDraft({ ...draft, name: e.currentTarget.value })}
            />
          </FieldRow>
          <SegmentedControl
            aria-label="Kind"
            data={[
              { value: 'QUEUE', label: 'Queue' },
              { value: 'ADDRESS', label: 'Address' },
            ]}
            value={draft.kind}
            onChange={(kind) => setDraft({ ...draft, kind: kind as Where['kind'] })}
            disabled={!draft.clusterId}
          />
          <div>
            <Button type="submit" loading={result.isFetching && !result.isPending}>
              Check access
            </Button>
          </div>
        </Stack>
      </form>

      {result.isPending ? <LoadingState label="Checking access" blockSize="12rem" /> : null}
      {result.isError ? <ErrorState error={result.error} onRetry={() => void result.refetch()} /> : null}
      {result.data ? (
        <Stack gap="sm">
          <Text size="sm" role="status">
            {allowed} of {result.data.length} permissions are allowed on {placeText(asked, askedCluster)}.
          </Text>
          <TextInput
            label="Filter by permission"
            leftSection={<IconSearch size="1rem" aria-hidden />}
            value={query}
            onChange={(e) => setQuery(e.currentTarget.value)}
          />
          <DataTable
            variant="static"
            label={`Access on ${placeText(asked, askedCluster)}`}
            storageKey="security.access-check"
            columns={columns}
            data={rows}
            rowKey={rowKey}
            empty={
              <EmptyState
                kind="filtered"
                title={`Nothing matches “${query}”`}
                description="No permission has that in its name."
                onClearFilters={() => setQuery('')}
              />
            }
          />
        </Stack>
      ) : null}
    </Section>
  );
}
