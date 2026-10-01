import { Fragment } from 'react';
import { ActionIcon, Group, Kbd, Popover, ScrollArea, Stack, Switch, Text, Tooltip } from '@mantine/core';
import { IconKeyboard } from '@tabler/icons-react';

import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { Section } from '../../ui/Section.tsx';
import { useFeatures } from '../features.ts';
import { NAV_GROUPS } from '../nav/groups.ts';
import { setShortcutsHelpOpen, useShortcutsHelpOpen, useSingleKeyShortcuts } from './shortcuts.ts';
import { viewHotkeys } from './useKeySequences.ts';

interface Row {
  keys: string[][];
  what: string;
}

function Keys({ keys }: Readonly<{ keys: string[][] }>) {
  return (
    <Group gap={6} wrap="nowrap">
      {keys.map((combo, i) => (
        <Fragment key={combo.join('+')}>
          {i > 0 ? (
            <Text span size="xs" c="dimmed">
              or
            </Text>
          ) : null}
          <Group gap={2} wrap="nowrap">
            {combo.map((k) => (
              <Kbd key={k} size="xs">
                {k}
              </Kbd>
            ))}
          </Group>
        </Fragment>
      ))}
    </Group>
  );
}

/** One group of shortcuts: what each does, then the keys that do it. */
function Shortcuts({ title, rows }: Readonly<{ title: string; rows: Row[] }>) {
  if (rows.length === 0) return null;
  return (
    <Section title={title} headingLevel={3}>
      <DescriptionList items={rows.map((row) => ({ term: row.what, value: <Keys keys={row.keys} /> }))} />
    </Section>
  );
}

/**
 * Every keyboard shortcut, in one place (ADR-0109), with the switch that turns the single-key ones
 * off: a header button and the popover it opens, like the refresh control beside it. `?` opens the
 * same popover; the button keeps it reachable when the single-key shortcuts are off.
 */
export function ShortcutsHelp() {
  const opened = useShortcutsHelpOpen();
  const [enabled, setEnabled] = useSingleKeyShortcuts();
  const hotkeys = viewHotkeys(useFeatures());
  const byGroup = NAV_GROUPS.map((group) => ({
    group,
    rows: [...hotkeys.entries()]
      .filter(([, item]) => item.group === group.id)
      .sort((a, b) => a[1].order - b[1].order)
      .map(([key, item]) => ({
        keys: [['g', key]],
        what: `Go to ${item.label}`,
      })),
  })).filter((g) => g.rows.length > 0);

  return (
    <Popover
      opened={opened}
      onChange={setShortcutsHelpOpen}
      position="bottom-end"
      width="27.5rem"
      shadow="md"
      withArrow
      trapFocus
      returnFocus
      // Its trigger sits in the fixed header and is never scrolled away; hiding a "detached" popover only
      // raced the position measurement and could leave it open but invisible.
      hideDetached={false}
    >
      <Popover.Target>
        <Tooltip label="Keyboard shortcuts (?)" disabled={opened}>
          <ActionIcon
            variant="subtle"
            color="gray"
            aria-label="Keyboard shortcuts"
            aria-keyshortcuts="Shift+Slash"
            aria-expanded={opened}
            aria-haspopup="dialog"
            onClick={() => setShortcutsHelpOpen(!opened)}
          >
            <IconKeyboard size="1.125rem" aria-hidden />
          </ActionIcon>
        </Tooltip>
      </Popover.Target>
      <Popover.Dropdown aria-label="Keyboard shortcuts" p={0}>
        <ScrollArea.Autosize mah="min(70vh, 40rem)" type="auto">
          <Stack p="md">
            <Section title="Keyboard shortcuts">
              <Switch
                checked={enabled}
                onChange={(e) => setEnabled(e.currentTarget.checked)}
                label="Single-key shortcuts"
                data-autofocus
                description="The shortcuts without ⌘ or Ctrl: go to a view, open this list, focus the filter. Turn them off if you use speech input, or if they get in your way. Kept in this browser."
              />

              <Shortcuts
                title="Everywhere"
                rows={[
                  {
                    keys: [
                      ['⌘', 'K'],
                      ['Ctrl', 'K'],
                    ],
                    what: 'Search views, clusters and queues',
                  },
                  {
                    keys: [
                      ['⌘', 'B'],
                      ['Ctrl', 'B'],
                    ],
                    what: 'Collapse or expand the sidebar',
                  },
                  ...(enabled
                    ? [
                        { keys: [['?']], what: 'Show this list' },
                        { keys: [['/']], what: "Focus the view's filter" },
                      ]
                    : []),
                ]}
              />

              {enabled ? (
                byGroup.map(({ group, rows }) => (
                  <Shortcuts key={group.id} title={`Go to — ${group.label}`} rows={rows} />
                ))
              ) : (
                <Text size="sm" c="dimmed">
                  Single-key shortcuts are off. Turn them on above to go to views with <Kbd size="xs">g</Kbd> and a
                  letter.
                </Text>
              )}

              <Shortcuts
                title="In a grid"
                rows={[
                  {
                    keys: [['↑'], ['↓'], ['←'], ['→']],
                    what: 'Move between cells',
                  },
                  {
                    keys: [['Home'], ['End']],
                    what: 'First or last cell of the row',
                  },
                  {
                    keys: [
                      ['Ctrl', 'Home'],
                      ['Ctrl', 'End'],
                    ],
                    what: 'First or last row',
                  },
                  { keys: [['Enter']], what: 'Open the row' },
                  {
                    keys: [['Space']],
                    what: 'Select the row, where rows can be selected',
                  },
                  {
                    keys: [['Shift', 'F10'], ['Menu']],
                    what: "Open the row's actions",
                  },
                  { keys: [['Ctrl', 'C']], what: "Copy the cell's full value" },
                ]}
              />

              <Shortcuts
                title="In the SQL console"
                rows={[
                  {
                    keys: [
                      ['⌘', 'Enter'],
                      ['Ctrl', 'Enter'],
                    ],
                    what: 'Run the query',
                  },
                  {
                    keys: [
                      ['⌘', '.'],
                      ['Ctrl', '.'],
                    ],
                    what: 'Cancel the running query, from the editor or the page',
                  },
                  { keys: [['Ctrl', 'Space']], what: 'Complete a column or queue name' },
                  {
                    keys: [['Escape']],
                    what: 'Close completion, then collapse the selection, then leave the editor; it never cancels',
                  },
                  {
                    keys: [['F8'], ['⌘', 'Shift', 'M'], ['Ctrl', 'Shift', 'M']],
                    what: 'Step to the next diagnostic, or list them',
                  },
                  {
                    keys: [['↑'], ['↓']],
                    what: 'On the separator: resize the editor and the results (Shift for a larger step)',
                  },
                  { keys: [['Home'], ['End']], what: 'On the separator: the smallest or the largest editor' },
                ]}
              />

              <Shortcuts
                title="In the topology"
                rows={[
                  { keys: [['←'], ['→']], what: 'Move between columns of nodes' },
                  { keys: [['↑'], ['↓']], what: 'Move within a column' },
                  { keys: [['Home'], ['End']], what: 'First or last node' },
                  { keys: [['Enter'], ['Space']], what: 'Choose the node and announce it' },
                  { keys: [['Escape']], what: 'Clear the choice, keeping focus on the node' },
                ]}
              />
            </Section>
          </Stack>
        </ScrollArea.Autosize>
      </Popover.Dropdown>
    </Popover>
  );
}
