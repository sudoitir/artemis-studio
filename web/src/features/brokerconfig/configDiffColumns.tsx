import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { Column } from '../../ui/table/index.ts';
import { majorityWords, outlierWords, type DiffRow } from './configDiffRows.ts';

/** The word a key's class carries, so what a difference means is never left to the viewer to guess. */
function classWord(row: DiffRow): string {
  if (row.classification === 'EXPECTED') return 'expected';
  if (row.classification === 'UNCLASSIFIED') return 'unclassified';
  return 'configuration';
}

/**
 * The comparison's columns. The key identifies a row and is shortened in the middle, keeping its
 * tail; the values are code, shortened at the end with the whole value on demand and copyable,
 * because a long acceptor URI is the very thing that differs. The state is a word, never carried by
 * colour alone, and a drifting row also says so in its class column.
 */
export function configDiffColumns(): Column<DiffRow>[] {
  return [
    { id: 'section', header: 'Section', accessor: (r) => r.sectionLabel, kind: 'text', priority: 'high', max: 20 },
    { id: 'key', header: 'Key', accessor: (r) => r.key, kind: 'identifier', priority: 'essential' },
    {
      id: 'majority',
      header: 'Majority',
      accessor: majorityWords,
      description: 'The value held by more than half of the nodes that have the key',
      kind: 'code',
      priority: 'essential',
    },
    {
      id: 'outliers',
      header: 'Differs',
      accessor: outlierWords,
      description: 'The nodes whose value is not the majority, with their value',
      kind: 'code',
      priority: 'essential',
    },
    {
      id: 'state',
      header: 'State',
      accessor: (r) => r.stateWord,
      cell: (r) => <StatusBadge tone={r.drift ? 'warning' : 'neutral'}>{r.stateWord}</StatusBadge>,
      kind: 'status',
      badge: true,
      priority: 'essential',
    },
    { id: 'class', header: 'Class', accessor: classWord, kind: 'status', priority: 'low' },
  ];
}
