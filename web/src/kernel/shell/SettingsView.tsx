import { Fragment, useRef } from 'react';
import { Tabs, Text, Title } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { SETTINGS_GROUPS, useSlot } from '../slots.ts';
import classes from './SettingsView.module.css';

/**
 * A cluster's Settings page: one tab per section the features contribute, under fixed headings in
 * the order an operator's reach widens — their own preferences, what Studio shares, this cluster,
 * then what plugins added (operator-ui spec). The open tab is in the URL, so it can be shared and
 * survives a reload.
 *
 * Arrow keys move between tabs without opening them; Enter or Space opens one and moves focus to
 * its heading, so a keyboard user reads the section they chose rather than staying in the list.
 */
export function SettingsView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as { tab?: string };
  const navigate = useNavigate();
  const headings = useRef(new Map<string, HTMLHeadingElement>());

  const sections = useSlot('settings.sections');
  const groups = SETTINGS_GROUPS.map((group) => ({
    ...group,
    sections: sections.filter((section) => (section.group ?? 'plugins') === group.id),
  })).filter((group) => group.sections.length > 0);
  const ordered = groups.flatMap((group) => group.sections);

  const tab = ordered.some((section) => section.id === search.tab) ? search.tab : ordered[0]?.id;

  const open = (id: string | null) => {
    if (!id) return;
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, tab: id }), replace: true });
    requestAnimationFrame(() => headings.current.get(id)?.focus());
  };

  return (
    <Tabs
      value={tab ?? null}
      onChange={open}
      orientation="vertical"
      activateTabWithKeyboard={false}
      keepMounted={false}
      classNames={{ list: classes.list, tab: classes.tab, panel: classes.panel }}
    >
      <Tabs.List aria-label="Settings sections">
        {groups.map((group) => (
          <Fragment key={group.id}>
            <Text role="presentation" className={classes.group} size="xs" fw={600} tt="uppercase" c="dimmed">
              {group.label}
            </Text>
            {group.sections.map(({ id, title }) => (
              <Tabs.Tab key={id} value={id}>
                {title}
              </Tabs.Tab>
            ))}
          </Fragment>
        ))}
      </Tabs.List>

      {ordered.map(({ id, title, Component }) => (
        <Tabs.Panel key={id} value={id}>
          <Title
            order={3}
            tabIndex={-1}
            mb="xs"
            ref={(el) => {
              if (el) headings.current.set(id, el);
              else headings.current.delete(id);
            }}
          >
            {title}
          </Title>
          <Component clusterId={clusterId} />
        </Tabs.Panel>
      ))}
    </Tabs>
  );
}
