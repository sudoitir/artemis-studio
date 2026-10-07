import { Fragment, useRef, type ReactNode } from 'react';
import { Tabs, Text } from '@mantine/core';
import { useNavigate, useSearch } from '@tanstack/react-router';

import classes from './GroupedTabs.module.css';

export interface GroupedTab {
  id: string;
  title: string;
  panel: ReactNode;
  /** Shown after the title in the list, such as a count or a marker; its words become part of the tab's name. */
  aside?: ReactNode;
}

export interface TabGroup {
  id: string;
  label: string;
  tabs: GroupedTab[];
}

/**
 * A page's sections as vertical tabs under fixed group headings, the open one beside the list. A
 * group with no tabs is left out. The open tab is the URL's `?tab`, so it can be shared and survives
 * a reload; any other value opens the first tab.
 *
 * Arrow keys move between tabs without opening them; Enter or Space opens one and moves focus to its
 * panel, which the tab names, so a keyboard user reads the section they chose rather than staying in
 * the list.
 */
export function GroupedTabs({ label, groups }: { label: string; groups: TabGroup[] }) {
  const search = useSearch({ strict: false }) as { tab?: string };
  const navigate = useNavigate();
  const panels = useRef(new Map<string, HTMLDivElement>());

  const shown = groups.filter((group) => group.tabs.length > 0);
  const ordered = shown.flatMap((group) => group.tabs);
  const tab = ordered.some((candidate) => candidate.id === search.tab) ? search.tab : ordered[0]?.id;

  const open = (id: string | null) => {
    if (!id) return;
    void navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, tab: id }), replace: true });
    requestAnimationFrame(() => panels.current.get(id)?.focus());
  };

  return (
    <Tabs
      value={tab ?? null}
      onChange={open}
      orientation="vertical"
      activateTabWithKeyboard={false}
      keepMounted={false}
      classNames={{
        root: classes.root,
        list: classes.list,
        tab: classes.tab,
        tabLabel: classes.tabLabel,
        tabSection: classes.tabSection,
        panel: classes.panel,
      }}
    >
      <Tabs.List aria-label={label}>
        {shown.map((group) => (
          <Fragment key={group.id}>
            <Text role="presentation" className={classes.group} size="xs" fw={600} tt="uppercase" c="dimmed">
              {group.label}
            </Text>
            {group.tabs.map(({ id, title, aside }) => (
              <Tabs.Tab key={id} value={id} rightSection={aside}>
                {title}
              </Tabs.Tab>
            ))}
          </Fragment>
        ))}
      </Tabs.List>

      {ordered.map(({ id, panel }) => (
        <Tabs.Panel
          key={id}
          value={id}
          tabIndex={-1}
          ref={(el) => {
            if (el) panels.current.set(id, el);
            else panels.current.delete(id);
          }}
        >
          {panel}
        </Tabs.Panel>
      ))}
    </Tabs>
  );
}
