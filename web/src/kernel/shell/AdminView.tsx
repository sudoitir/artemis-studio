import { Tabs } from '@mantine/core';
import { useNavigate, useSearch } from '@tanstack/react-router';

import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { useSlot } from '../slots.ts';
import classes from './Views.module.css';

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
    <div className={classes.page}>
      <Page>
        <PageHeader
          title="Administration"
          description="What applies to the whole installation: who can sign in and what they may do, the environments, and what is installed."
        />

        <Tabs value={tab ?? null} onChange={setTab}>
          <Tabs.List aria-label="Administration sections">
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
      </Page>
    </div>
  );
}
