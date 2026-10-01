import type { Column } from '../../ui/table/index.ts';
import type { PluginUpdateView, PluginView } from './api.ts';
import {
  fixCell,
  fixLabel,
  licenseCell,
  licenseText,
  pluginCell,
  pluginText,
  statusCell,
  statusText,
  versionCell,
  versionText,
} from './cells.tsx';
import { contributionSummary } from './words.ts';

/** What the plugins table's cells act on, besides the rows. */
export interface PluginColumnDeps {
  /** The newest version the last update check found, by plugin id. */
  updates: ReadonlyMap<string, PluginUpdateView>;
  /** Starts updating a plugin. */
  onUpdate: (id: string) => void;
  /** Opens a plugin's details. */
  onOpen: (id: string) => void;
  /** Whether any plugin needs a license, which adds the license column. */
  showLicense: boolean;
}

/**
 * The plugins table's columns. The plugin identifies a row and is never hidden; its state and
 * version come next, so a plugin that needs someone stays in view, and what it adds is the first to
 * go when the table is narrow.
 */
export function pluginColumns({ updates, onUpdate, onOpen, showLicense }: PluginColumnDeps): Column<PluginView>[] {
  return [
    {
      id: 'plugin',
      header: 'Plugin',
      accessor: pluginText,
      cell: pluginCell,
      kind: 'identifier',
      priority: 'essential',
      // The icon, which the text does not measure.
      min: 24,
    },
    {
      id: 'version',
      header: 'Version',
      accessor: (p) => versionText(p, updates.get(p.id)),
      cell: (p) => versionCell(p, updates.get(p.id), onUpdate),
      kind: 'status',
      priority: 'high',
    },
    { id: 'status', header: 'Status', accessor: statusText, cell: statusCell, kind: 'status', priority: 'high' },
    ...(showLicense
      ? [
          {
            id: 'license',
            header: 'License',
            accessor: licenseText,
            cell: licenseCell,
            kind: 'status',
            priority: 'high',
            badge: true,
          } satisfies Column<PluginView>,
        ]
      : []),
    { id: 'adds', header: 'Adds', accessor: (p) => contributionSummary(p.info), kind: 'text', priority: 'low' },
    {
      id: 'fix',
      header: 'Fix',
      accessor: fixLabel,
      cell: (p) => fixCell(p, onOpen),
      kind: 'status',
      priority: 'high',
      // The button's own padding, which the label does not measure.
      min: 12,
    },
  ];
}
