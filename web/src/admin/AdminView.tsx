import { Stack, Tabs, Title } from '@mantine/core';
import { useNavigate, useSearch } from '@tanstack/react-router';

import { UsersPanel } from './UsersPanel.tsx';
import { RolesPanel } from './RolesPanel.tsx';
import { EnvironmentsPanel } from './EnvironmentsPanel.tsx';
import { GroupMappingPanel } from './GroupMappingPanel.tsx';

/** Users, roles, environments, and identity provider group mappings (authorization spec). */
export function AdminView() {
  const search = useSearch({ strict: false }) as { tab?: string };
  const navigate = useNavigate();

  const tab = search.tab ?? 'users';
  const setTab = (v: string | null) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, tab: v ?? undefined }) });

  return (
    <Stack gap="md" p="lg">
      <Title order={3}>Administration</Title>

      <Tabs value={tab} onChange={setTab}>
        <Tabs.List>
          <Tabs.Tab value="users">Users</Tabs.Tab>
          <Tabs.Tab value="roles">Roles</Tabs.Tab>
          <Tabs.Tab value="environments">Environments</Tabs.Tab>
          <Tabs.Tab value="group-mappings">Group mappings</Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="users" pt="md">
          <UsersPanel />
        </Tabs.Panel>
        <Tabs.Panel value="roles" pt="md">
          <RolesPanel />
        </Tabs.Panel>
        <Tabs.Panel value="environments" pt="md">
          <EnvironmentsPanel />
        </Tabs.Panel>
        <Tabs.Panel value="group-mappings" pt="md">
          <GroupMappingPanel />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
