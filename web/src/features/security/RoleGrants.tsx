import { useState, type ReactNode } from 'react';
import { Drawer, Stack, TextInput } from '@mantine/core';
import { IconSearch } from '@tabler/icons-react';

import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useEffectivePermissions, type EffectivePermissionView } from './api.ts';
import { effectColumns } from './columns.ts';

function scopeLabel(v: EffectivePermissionView): string {
  if (v.scopeType === 'GLOBAL') return 'Global';
  const kind = v.scopeType === 'CLUSTER' ? 'Cluster' : 'Environment';
  return `${kind} ${v.scopeId?.slice(0, 8) ?? ''}`;
}

const rowKey = (v: EffectivePermissionView) => `${v.action}-${v.roleId}-${v.via}-${v.scopeType}-${v.scopeId ?? ''}`;

/** What stands in for the permissions while they load, fail to load, or the user holds no role. */
function permissionsNotice(result: ReturnType<typeof useEffectivePermissions>): ReactNode {
  if (result.isPending) return <LoadingState label="Loading effective permissions" blockSize="16rem" />;
  if (result.isError) return <ErrorState error={result.error} onRetry={() => void result.refetch()} />;
  if (result.data.length === 0) {
    return (
      <EmptyState
        kind="empty"
        title="This user holds no role"
        description="So they can do nothing. Grant a role from the users table."
      />
    );
  }
  return null;
}

/** A user's effective permissions per scope, with the role and wildcard each came through (operator-ui spec). */
export function EffectivePermissionsDrawer({
  user,
  onClose,
}: Readonly<{
  user: { id: string; username: string } | null;
  onClose: () => void;
}>) {
  const result = useEffectivePermissions(user?.id ?? null);
  const [query, setQuery] = useState('');
  const q = query.trim().toLowerCase();
  const rows = (result.data ?? []).filter(
    (v) => q === '' || v.action.toLowerCase().includes(q) || v.roleName.toLowerCase().includes(q),
  );
  const scopes = [...new Set(rows.map(scopeLabel))];
  const columns = effectColumns();

  return (
    <Drawer
      opened={user !== null}
      onClose={onClose}
      position="right"
      size="xl"
      title={user ? `Effective permissions of ${user.username}` : ''}
    >
      {permissionsNotice(result) ?? (
        <Stack gap="md">
          <TextInput
            label="Filter by permission or role"
            leftSection={<IconSearch size="1rem" aria-hidden />}
            value={query}
            onChange={(e) => setQuery(e.currentTarget.value)}
          />
          {rows.length === 0 ? (
            <EmptyState
              kind="filtered"
              title={`Nothing matches “${query}”`}
              description="No permission or role has that in its name."
              onClearFilters={() => setQuery('')}
            />
          ) : (
            scopes.map((scope) => (
              <Section key={scope} title={scope} headingLevel={3}>
                <DataTable
                  variant="static"
                  label={`Permissions at ${scope}`}
                  columns={columns}
                  data={rows.filter((v) => scopeLabel(v) === scope)}
                  rowKey={rowKey}
                  empty={null}
                />
              </Section>
            ))
          )}
        </Stack>
      )}
    </Drawer>
  );
}
