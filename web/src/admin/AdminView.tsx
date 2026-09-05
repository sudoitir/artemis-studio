import { Stack, Tabs, Title } from '@mantine/core';
import { useNavigate, useSearch } from '@tanstack/react-router';

import { UsersPanel } from './UsersPanel.tsx';
import { RolesPanel } from './RolesPanel.tsx';
import { EnvironmentsPanel } from './EnvironmentsPanel.tsx';
import { TokensPanel } from './TokensPanel.tsx';
import { OidcMappingPanel } from './OidcMappingPanel.tsx';

/** Users, roles, environments, API tokens, and OIDC mapping administration (authorization spec). */
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
          <Tabs.Tab value="tokens">API tokens</Tabs.Tab>
          <Tabs.Tab value="oidc">SSO mapping</Tabs.Tab>
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
        <Tabs.Panel value="tokens" pt="md">
          <TokensPanel />
        </Tabs.Panel>
        <Tabs.Panel value="oidc" pt="md">
          <OidcMappingPanel />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
