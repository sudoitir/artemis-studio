import { useParams } from '@tanstack/react-router';

import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { SETTINGS_GROUPS, useSlot } from '../slots.ts';
import { GroupedTabs } from './GroupedTabs.tsx';

/**
 * A cluster's Settings page: one tab per section the features contribute, under fixed headings in
 * the order an operator's reach widens — their own preferences, what Studio shares, this cluster,
 * then what plugins added (operator-ui spec). The open tab is in the URL.
 */
export function SettingsView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const sections = useSlot('settings.sections');

  const groups = SETTINGS_GROUPS.map((group) => ({
    ...group,
    tabs: sections
      .filter((section) => (section.group ?? 'plugins') === group.id)
      .map(({ id, title, Component }) => ({
        id,
        title: title ?? id,
        panel: (
          <Section title={title ?? id}>
            <Component clusterId={clusterId} />
          </Section>
        ),
      })),
  }));

  return (
    <Page>
      <PageHeader
        title="Settings"
        description="Your own preferences, what Studio shares across clusters, and this cluster's configuration."
      />
      <GroupedTabs label="Settings sections" groups={groups} />
    </Page>
  );
}
