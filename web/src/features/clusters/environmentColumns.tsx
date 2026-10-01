import { ActionIcon, ColorSwatch } from '@mantine/core';
import { IconPencil, IconTrash } from '@tabler/icons-react';

import type { Column } from '../../ui/table/index.ts';
import type { EnvironmentView } from './api.ts';
import classes from './Clusters.module.css';

/** The environments table: the colour with its value in words, the name, the order, and the row's controls. */
export function environmentColumns(onEdit: (e: EnvironmentView) => void, onDelete: (e: EnvironmentView) => void) {
  return [
    {
      id: 'name',
      header: 'Name',
      accessor: (e) => e.name,
      kind: 'identifier',
      priority: 'essential',
      wrap: true,
    },
    {
      id: 'colour',
      header: 'Colour',
      accessor: (e) => e.colour ?? 'None',
      cell: (e) => (
        <span className={classes.controls}>
          {e.colour ? <ColorSwatch component="span" color={e.colour} size="1rem" /> : null}
          {e.colour ?? 'None'}
        </span>
      ),
      kind: 'status',
      priority: 'high',
      // The swatch, which the text does not measure.
      min: 12,
    },
    { id: 'order', header: 'Order', accessor: (e) => e.sortOrder, kind: 'number', priority: 'high' },
    {
      id: 'actions',
      header: 'Actions',
      accessor: () => 'Edit Delete',
      cell: (e) => (
        <span className={classes.controls}>
          <ActionIcon variant="subtle" onClick={() => onEdit(e)} aria-label={`Edit ${e.name}`}>
            <IconPencil size="1rem" aria-hidden />
          </ActionIcon>
          <ActionIcon variant="subtle" onClick={() => onDelete(e)} aria-label={`Delete ${e.name}`}>
            <IconTrash size="1rem" aria-hidden />
          </ActionIcon>
        </span>
      ),
      kind: 'status',
      priority: 'essential',
      min: 8,
    },
  ] satisfies Column<EnvironmentView>[];
}
