import { Button, Checkbox, Divider, Popover, Radio, Stack, VisuallyHidden } from '@mantine/core';
import { IconColumns3 } from '@tabler/icons-react';

import classes from './DataTable.module.css';
import type { Density } from './density.ts';

export interface ColumnsMenuEntry {
  id: string;
  header: string;
  visible: boolean;
  /** A column that identifies a row or names a node is always shown. */
  locked: boolean;
}

/**
 * The Columns control, always in the table's toolbar with its space reserved so the table's width
 * never depends on whether anything is hidden. Its name carries how many columns are hidden, whether
 * the viewer or a narrow window hid them; it lets the viewer show or hide each, choose the density
 * and put widths back to fitting.
 */
export function ColumnsMenu({
  columns,
  hiddenCount,
  onToggle,
  density,
  onDensity,
  canResetWidths,
  onResetWidths,
}: Readonly<{
  columns: ColumnsMenuEntry[];
  hiddenCount: number;
  onToggle: (id: string, visible: boolean) => void;
  density: Density;
  onDensity: (density: Density) => void;
  canResetWidths: boolean;
  onResetWidths: () => void;
}>) {
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
            {columns.map((column) => (
              <Checkbox
                key={column.id}
                size="xs"
                label={column.header}
                checked={column.visible}
                disabled={column.locked}
                onChange={(e) => onToggle(column.id, e.currentTarget.checked)}
              />
            ))}
          </Stack>
          <Divider />
          <Radio.Group label="Row density" value={density} onChange={(value) => onDensity(value as Density)}>
            <Stack gap="xs" mt="xs">
              <Radio size="xs" value="compact" label="Compact" />
              <Radio size="xs" value="comfortable" label="Comfortable" />
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
