import { createContext, Fragment, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { Tabs, Text, VisuallyHidden } from '@mantine/core';
import { useNavigate, useSearch } from '@tanstack/react-router';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
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

/** Lets an open panel say it holds input that leaving the tab would lose. */
const UnsavedContext = createContext<((dirty: boolean) => void) | null>(null);

/**
 * Called by a form inside a grouped tab's panel with whether it holds unsaved input. While it does, the tab
 * is marked Unsaved and switching to another tab asks first, because the panel unmounts on a switch and
 * the input would be lost. Outside grouped tabs it does nothing.
 */
export function useUnsavedTab(dirty: boolean) {
  const report = useContext(UnsavedContext);
  useEffect(() => {
    report?.(dirty);
    return () => report?.(false);
  }, [report, dirty]);
}

/**
 * A page's sections as vertical tabs under fixed group headings, the open one beside the list. A
 * group with no tabs is left out. The open tab is the URL's `?tab`, so it can be shared and survives
 * a reload; any other value opens the first tab.
 *
 * Arrow keys move between tabs without opening them; Enter or Space opens one and moves focus to its
 * panel, which the tab names, so a keyboard user reads the section they chose rather than staying in
 * the list. Leaving a tab whose form holds unsaved input ({@link useUnsavedTab}) asks first.
 */
export function GroupedTabs({ label, groups }: Readonly<{ label: string; groups: TabGroup[] }>) {
  const search = useSearch({ strict: false }) as { tab?: string };
  const navigate = useNavigate();
  const panels = useRef(new Map<string, HTMLDivElement>());
  const [unsaved, setUnsaved] = useState(false);
  const [leavingFor, setLeavingFor] = useState<string | null>(null);

  const shown = groups.filter((group) => group.tabs.length > 0);
  const ordered = shown.flatMap((group) => group.tabs);
  const tab = ordered.some((candidate) => candidate.id === search.tab) ? search.tab : ordered[0]?.id;

  const go = (id: string) => {
    void navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, tab: id }),
      replace: true,
      resetScroll: false,
    });
    // The panel takes focus without the page jumping to it: the tab list beside it stays where it was.
    requestAnimationFrame(() => panels.current.get(id)?.focus({ preventScroll: true }));
  };

  const open = (id: string | null) => {
    if (!id) return;
    if (id === tab) {
      requestAnimationFrame(() => panels.current.get(id)?.focus({ preventScroll: true }));
      return;
    }
    if (unsaved) setLeavingFor(id);
    else go(id);
  };

  return (
    <UnsavedContext.Provider value={setUnsaved}>
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
                <Tabs.Tab
                  key={id}
                  value={id}
                  rightSection={
                    unsaved && id === tab ? (
                      <>
                        {aside}
                        <span className={classes.unsaved} aria-hidden>
                          Unsaved
                        </span>
                        <VisuallyHidden>, unsaved changes</VisuallyHidden>
                      </>
                    ) : (
                      aside
                    )
                  }
                >
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
      <ConfirmDialog
        opened={leavingFor !== null}
        onClose={() => setLeavingFor(null)}
        title="Discard changes?"
        consequence="This section has changes that are not saved. Opening another section discards them; stay to save them first."
        confirmLabel="Discard and switch"
        dismissLabel="Stay on this section"
        tone="danger"
        onConfirm={() => {
          const next = leavingFor;
          setLeavingFor(null);
          setUnsaved(false);
          if (next) go(next);
        }}
      />
    </UnsavedContext.Provider>
  );
}
