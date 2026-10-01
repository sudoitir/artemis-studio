import type { Column } from '../../ui/table/index.ts';
import type { ConfigEntryView } from './api.ts';
import { ClassificationBadge } from './configDiffCells.tsx';

/** The word a classified entry carries beside its status; only drift is a problem, the others keep it legible. */
function classificationWord(entry: ConfigEntryView): string {
  if (entry.classification === 'EXPECTED') return 'expected';
  if (entry.classification === 'UNCLASSIFIED') return 'unclassified';
  return entry.drift ? 'drift' : '';
}

/**
 * The columns of one section of a two-node comparison. The key identifies a row; both values wrap
 * rather than shorten, because a long acceptor URI is the very thing that differs. The status is a
 * word, never carried by colour alone, and a row that drifts also says so.
 */
export function diffEntryColumns(): Column<ConfigEntryView>[] {
  return [
    { id: 'key', header: 'Key', accessor: (e) => e.key, kind: 'code', priority: 'essential', wrap: true },
    { id: 'left', header: 'Left', accessor: (e) => e.left ?? '—', kind: 'code', priority: 'essential', wrap: true },
    { id: 'right', header: 'Right', accessor: (e) => e.right ?? '—', kind: 'code', priority: 'essential', wrap: true },
    {
      id: 'status',
      header: 'Status',
      accessor: (e) => `${e.statusWord} ${classificationWord(e)}`,
      cell: (e) => (
        <>
          {e.statusWord} <ClassificationBadge entry={e} />
        </>
      ),
      kind: 'text',
      badge: true,
      priority: 'essential',
      wrap: true,
    },
  ];
}
