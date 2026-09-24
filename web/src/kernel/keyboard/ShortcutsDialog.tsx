import { Fragment } from 'react';
import { Group, Kbd, Modal, Stack, Switch, Table, Text, Title } from '@mantine/core';

import { useFeatures } from '../features.ts';
import { NAV_GROUPS } from '../nav/groups.ts';
import { setShortcutsHelpOpen, useShortcutsHelpOpen, useSingleKeyShortcuts } from './shortcuts.ts';
import { viewHotkeys } from './useKeySequences.ts';

interface Row {
  keys: string[][];
  what: string;
}

function Keys({ keys }: { keys: string[][] }) {
  return (
    <Group gap={6} wrap="nowrap">
      {keys.map((combo, i) => (
        <Fragment key={i}>
          {i > 0 ? (
            <Text span size="xs" c="dimmed">
              or
            </Text>
          ) : null}
          <Group gap={2} wrap="nowrap">
            {combo.map((k, j) => (
              <Kbd key={j} size="xs">
                {k}
              </Kbd>
            ))}
          </Group>
        </Fragment>
      ))}
    </Group>
  );
}

function Section({ title, rows }: { title: string; rows: Row[] }) {
  if (rows.length === 0) return null;
  return (
    <Stack gap={4}>
      <Title order={3} fz="sm">
        {title}
      </Title>
      <Table withRowBorders={false} verticalSpacing={4}>
        <Table.Tbody>
          {rows.map((row) => (
            <Table.Tr key={row.what}>
              <Table.Td w={200}>
                <Keys keys={row.keys} />
              </Table.Td>
              <Table.Td>
                <Text size="sm">{row.what}</Text>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

/**
 * Every keyboard shortcut, in one place (ADR-0109), with the switch that turns the single-key ones
 * off. Opened by `?`, from the header, and from the palette — so it stays reachable when the
 * single-key shortcuts are off.
 */
export function ShortcutsDialog() {
  const opened = useShortcutsHelpOpen();
  const [enabled, setEnabled] = useSingleKeyShortcuts();
  const hotkeys = viewHotkeys(useFeatures());
  const byGroup = NAV_GROUPS.map((group) => ({
    group,
    rows: [...hotkeys.entries()]
      .filter(([, item]) => item.group === group.id)
      .sort((a, b) => a[1].order - b[1].order)
      .map(([key, item]) => ({ keys: [['g', key]], what: `Go to ${item.label}` })),
  })).filter((g) => g.rows.length > 0);

  return (
    <Modal opened={opened} onClose={() => setShortcutsHelpOpen(false)} title="Keyboard shortcuts" size="lg">
      <Stack gap="lg">
        <Switch
          checked={enabled}
          onChange={(e) => setEnabled(e.currentTarget.checked)}
          label="Single-key shortcuts"
          description="The shortcuts without ⌘ or Ctrl: go to a view, open this list, focus the filter. Turn them off if you use speech input, or if they get in your way. Kept in this browser."
        />

        <Section
          title="Everywhere"
          rows={[
            { keys: [['⌘', 'K'], ['Ctrl', 'K']], what: 'Search views, clusters and queues' },
            { keys: [['⌘', 'B'], ['Ctrl', 'B']], what: 'Collapse or expand the sidebar' },
            ...(enabled
              ? [
                  { keys: [['?']], what: 'Show this list' },
                  { keys: [['/']], what: "Focus the view's filter" },
                ]
              : []),
          ]}
        />

        {enabled ? (
          byGroup.map(({ group, rows }) => <Section key={group.id} title={`Go to — ${group.label}`} rows={rows} />)
        ) : (
          <Text size="sm" c="dimmed">
            Single-key shortcuts are off. Turn them on above to go to views with <Kbd size="xs">g</Kbd> and a letter.
          </Text>
        )}

        <Section
          title="In a grid"
          rows={[
            { keys: [['↑'], ['↓'], ['←'], ['→']], what: 'Move between cells' },
            { keys: [['Home'], ['End']], what: 'First or last cell of the row' },
            { keys: [['Ctrl', 'Home'], ['Ctrl', 'End']], what: 'First or last row' },
            { keys: [['Enter']], what: 'Open the row' },
            { keys: [['Space']], what: 'Select the row, where rows can be selected' },
            { keys: [['Shift', 'F10'], ['Menu']], what: "Open the row's actions" },
            { keys: [['Ctrl', 'C']], what: "Copy the cell's full value" },
          ]}
        />
      </Stack>
    </Modal>
  );
}
