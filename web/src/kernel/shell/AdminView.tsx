import { Stack, Tabs, Title } from '@mantine/core';
import { useNavigate, useSearch } from '@tanstack/react-router';

import { useSlot } from '../slots.ts';

/**
 * Studio-wide administration (authorization spec): one tab per contribution, such as users, roles,
 * environments and identity provider group mappings. The open tab is in the URL.
 */
export function AdminView() {
  const search = useSearch({ strict: false }) as { tab?: string };
  const navigate = useNavigate();
  const tabs = useSlot('admin.tabs');

  const tab = tabs.some((candidate) => candidate.id === search.tab) ? search.tab : tabs[0]?.id;
  const setTab = (v: string | null) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, tab: v ?? undefined }) });

  return (
    <Stack gap="md" p="lg">
      <Title order={3}>Administration</Title>

      <Tabs value={tab ?? null} onChange={setTab}>
        <Tabs.List>
          {tabs.map(({ id, title }) => (
            <Tabs.Tab key={id} value={id}>
              {title}
            </Tabs.Tab>
          ))}
        </Tabs.List>

        {tabs.map(({ id, Component }) => (
          <Tabs.Panel key={id} value={id} pt="md">
            <Component />
          </Tabs.Panel>
        ))}
      </Tabs>
    </Stack>
  );
}
