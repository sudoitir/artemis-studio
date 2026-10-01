import { useLayoutEffect, useRef } from 'react';
import { ActionIcon, Button, Checkbox, Divider, Group, Popover, Radio, Stack, VisuallyHidden } from '@mantine/core';
import { IconArrowDown, IconArrowUp, IconColumns3 } from '@tabler/icons-react';

import classes from './DataTable.module.css';
import type { Density } from './density.ts';

interface ColumnsMenuEntry {
  id: string;
  header: string;
  visible: boolean;
  /** A column that identifies a row or names a node is always shown. */
  locked: boolean;
}

type Move = 'earlier' | 'later';

/**
 * The Columns control, always in the table's toolbar with its space reserved so the table's width
 * never depends on whether anything is hidden. Its name carries how many columns are hidden, whether
 * the viewer or a narrow window hid them; it lets the viewer show or hide each, move each earlier or
 * later, choose the density and put widths back to fitting. The first column stays first.
 */
export function ColumnsMenu({
  columns,
  hiddenCount,
  onToggle,
  onMove,
  density,
  onDensity,
  canResetWidths,
  onResetWidths,
}: Readonly<{
  /** Every column in the order now shown. */
  columns: ColumnsMenuEntry[];
  hiddenCount: number;
  onToggle: (id: string, visible: boolean) => void;
  onMove: (id: string, by: -1 | 1) => void;
  density: Density;
  onDensity: (density: Density) => void;
  canResetWidths: boolean;
  onResetWidths: () => void;
}>) {
  const buttons = useRef(new Map<string, HTMLButtonElement>());
  const pressed = useRef<{ id: string; move: Move } | null>(null);

  // A moved row is re-inserted, which drops focus, and a button at a bound is disabled, which cannot
  // hold it. Focus returns to the pressed button, or to its sibling when that one can go no further.
  useLayoutEffect(() => {
    const press = pressed.current;
    if (!press) return;
    pressed.current = null;
    const own = buttons.current.get(`${press.id}:${press.move}`);
    const sibling = buttons.current.get(`${press.id}:${press.move === 'earlier' ? 'later' : 'earlier'}`);
    (own && !own.disabled ? own : sibling)?.focus();
  });

  const move = (id: string, move: Move) => {
    pressed.current = { id, move };
    onMove(id, move === 'earlier' ? -1 : 1);
  };
  const moveButton = (column: ColumnsMenuEntry, index: number, direction: Move) => {
    const earlier = direction === 'earlier';
    const atBound = earlier ? index <= 1 : index === columns.length - 1;
    const Icon = earlier ? IconArrowUp : IconArrowDown;
    return (
      <ActionIcon
        ref={(node: HTMLButtonElement | null) => {
          if (node) buttons.current.set(`${column.id}:${direction}`, node);
          else buttons.current.delete(`${column.id}:${direction}`);
        }}
        variant="subtle"
        color="graphite"
        size="sm"
        aria-label={`Move ${column.header} ${direction}`}
        disabled={index === 0 || atBound}
        onClick={() => move(column.id, direction)}
      >
        <Icon size="1em" aria-hidden />
      </ActionIcon>
    );
  };

  return (
    <Popover position="bottom-end" shadow="md" trapFocus returnFocus hideDetached={false}>
      <Popover.Target>
        <Button
          variant="default"
          size="compact-sm"
          className={classes.columnsButton}
          leftSection={<IconColumns3 size="1em" aria-hidden />}
        >
          Columns
          <VisuallyHidden>{hiddenCount > 0 ? `, ${hiddenCount} hidden` : ''}</VisuallyHidden>
          <span aria-hidden="true" className={classes.columnsCount}>
            {hiddenCount > 0 ? hiddenCount : ''}
          </span>
        </Button>
      </Popover.Target>
      <Popover.Dropdown>
        <Stack gap="sm" className={classes.columnsPanel}>
          <Stack gap="xs" role="group" aria-label="Show columns">
            {columns.map((column, index) => (
              <Group key={column.id} gap="xs" wrap="nowrap" justify="space-between">
                <Checkbox
                  size="xs"
                  label={column.header}
                  checked={column.visible}
                  disabled={column.locked}
                  onChange={(e) => onToggle(column.id, e.currentTarget.checked)}
                />
                <Group gap={2} wrap="nowrap">
                  {moveButton(column, index, 'earlier')}
                  {moveButton(column, index, 'later')}
                </Group>
              </Group>
            ))}
          </Stack>
          <Divider />
          <Radio.Group label="Row density" value={density} onChange={(value) => onDensity(value as Density)}>
            <Stack gap="xs" mt="xs">
              <Radio size="md" value="compact" label="Compact" />
              <Radio size="md" value="comfortable" label="Comfortable" />
            </Stack>
          </Radio.Group>
          <Divider />
          <Button variant="default" size="compact-sm" disabled={!canResetWidths} onClick={onResetWidths}>
            Reset widths
          </Button>
        </Stack>
      </Popover.Dropdown>
    </Popover>
  );
}
